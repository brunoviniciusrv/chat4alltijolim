package chat4all.worker.processing;

import chat4all.shared.MessageEvent;
import chat4all.worker.cassandra.CassandraMessageStore;
import chat4all.worker.cassandra.MessageEntity;
import chat4all.worker.metrics.WorkerMetricsRegistry;
import chat4all.worker.routing.ConnectorRouter;
import chat4all.worker.notifications.RedisNotificationPublisher;

import java.time.Instant;

/**
 * MessageProcessor - Lógica de negócio para processar mensagens do Kafka
 * 
 * PROPÓSITO EDUCACIONAL: Business Logic + Store-and-Forward Pattern
 * ==================
 * 
 * STORE-AND-FORWARD PATTERN:
 * - Store: Persistir mensagem no banco (SENT)
 * - Forward: Simular entrega ao destinatário
 * - Update: Marcar como DELIVERED
 * 
 * FLUXO DETALHADO:
 * ```
 * Kafka → MessageProcessor → [1] Deduplicação
 *                              ↓ (se nova)
 *                           [2] Save (status=SENT)
 *                              ↓
 *                           [3] Simulate Delivery (sleep)
 *                              ↓
 *                           [4] Update (status=DELIVERED)
 * ```
 * 
 * POR QUE ESSE PADRÃO?
 * - Garante durabilidade: mensagem não se perde se worker crashar
 * - Permite retry: se delivery falhar, retentar depois
 * - Audit trail: saber quando mensagem foi recebida vs entregue
 * 
 * STATUS TRANSITIONS:
 * - SENT: Mensagem persistida, aguardando entrega
 * - DELIVERED: Entrega confirmada (destinatário recebeu)
 * - READ: (futuro) Destinatário leu a mensagem
 * 
 * IDEMPOTÊNCIA:
 * - Processar mesma mensagem 2x não duplica no banco
 * - message_id único previne duplicação
 * 
 * @author Chat4All Educational Project
 */
public class MessageProcessor {
    
    private final CassandraMessageStore messageStore;
    private final ConnectorRouter connectorRouter;
    private final WorkerMetricsRegistry metricsRegistry;
    private final RedisNotificationPublisher notificationPublisher;
    
    /**
     * Cria MessageProcessor
     * 
     * @param messageStore Store para persistir mensagens
     * @param connectorRouter Router para conectores externos (WhatsApp, Instagram, etc.)
     * @param notificationPublisher Publisher para notificações via Redis (opcional)
     */
    public MessageProcessor(
        CassandraMessageStore messageStore, 
        ConnectorRouter connectorRouter,
        RedisNotificationPublisher notificationPublisher
    ) {
        this.messageStore = messageStore;
        this.connectorRouter = connectorRouter;
        this.metricsRegistry = WorkerMetricsRegistry.getInstance();
        this.notificationPublisher = notificationPublisher;
    }
    
    /**
     * Processa mensagem consumida do Kafka
     * 
     * FLUXO COMPLETO:
     * 
     * [1] DEDUPLICAÇÃO:
     *     - Verifica se message_id já existe no banco
     *     - Se existe: SKIP (mensagem duplicada do Kafka)
     *     - Se não existe: continuar processamento
     * 
     * [2] PERSIST (status=SENT):
     *     - Converter MessageEvent → MessageEntity
     *     - INSERT no Cassandra
     *     - Se falhar: throw exception (Kafka não commitará offset)
     * 
     * [3] SIMULATE DELIVERY:
     *     - Sleep 100ms (simula latência de rede)
     *     - Em produção: chamar API externa, enviar push notification, etc.
     * 
     * [4] UPDATE STATUS (status=DELIVERED):
     *     - UPDATE no Cassandra
     *     - Marca mensagem como entregue
     * 
     * EDUCATIONAL NOTE: Error Handling
     * - Se [2] falhar: exception → Kafka retry
     * - Se [3] falhar: mensagem fica SENT → job async retentar depois
     * - Se [4] falhar: mensagem fica SENT → eventual consistency
     * 
     * KAFKA COMMIT:
     * - Só commitamos offset DEPOIS de process() retornar sem exception
     * - Se crashar no meio: Kafka reenvia (dedup protege duplicação)
     * 
     * @param event MessageEvent do Kafka
     * @return true se processou, false se duplicada/erro
     */
    public boolean process(MessageEvent event) {
        String messageId = event.getMessageId();
        String conversationId = event.getConversationId();
        
        long startTime = System.currentTimeMillis();
        
        System.out.println("\n▶ Processing message: " + messageId + 
                         " (conv: " + conversationId + ")");
        
        try {
            // [1] DEDUPLICAÇÃO - Verificar se mensagem já existe
            if (messageStore.messageExists(messageId)) {
                System.out.println("⊗ SKIP: Message " + messageId + " already processed (duplicate)");
                long duration = System.currentTimeMillis() - startTime;
                metricsRegistry.recordMessageProcessed("DUPLICATE", duration);
                return false; // Duplicada, mas não é erro (retorna success para commitar offset)
            }
            
            // [2] PERSIST - Salvar mensagem com status SENT (Phase 2: includes file attachment)
            long cassandraStart = System.currentTimeMillis();
            MessageEntity entity = new MessageEntity(
                event.getConversationId(),
                Instant.ofEpochMilli(event.getTimestamp()),
                event.getMessageId(),
                event.getSenderId(),
                event.getContent(),
                "SENT", // Status inicial
                event.getFileId(), // Phase 2: file attachment
                event.getFileMetadata() // Phase 2: file metadata
            );
            
            boolean saved = messageStore.saveMessage(entity);
            long cassandraDuration = System.currentTimeMillis() - cassandraStart;
            metricsRegistry.recordCassandraWrite(cassandraDuration, saved);
            
            if (!saved) {
                metricsRegistry.recordMessageFailed("cassandra_error");
                throw new RuntimeException("Failed to save message to Cassandra");
            }
            
            System.out.println("✓ [1/2] Saved with status=SENT");
            
            // DEBUG: Check what we have
            System.out.println("[DEBUG] recipient_id from event: " + event.getRecipientId());
            System.out.println("[DEBUG] sender_id from event: " + event.getSenderId());
            System.out.println("[DEBUG] conversation_id: " + conversationId);
            
            // [3] ROUTE OR DELIVER - Check if should route to external connector
            // Determinar recipientId:
            // 1. Se recipient_id presente no evento → usar
            // 2. Se conversação 1:1 (format: direct_user_xxx_user_yyy) → extrair outro participante
            // 3. Se grupo (format: group_xxx) → notificar todos participantes (TODO)
            String recipientId = event.getRecipientId();
            if (recipientId == null || recipientId.isEmpty()) {
                // Tentar extrair recipientId do conversation_id
                if (conversationId.startsWith("direct_")) {
                        // Format esperado: direct_user_<uuid>_user_<uuid>
                        String withoutPrefix = conversationId.substring("direct_".length());

                        // Remover o primeiro "user_" e dividir o resto para recuperar os dois IDs
                        if (withoutPrefix.startsWith("user_")) {
                            String withoutFirstUser = withoutPrefix.substring("user_".length());
                            String[] parts = withoutFirstUser.split("_user_");

                            if (parts.length == 2) {
                                String userA = "user_" + parts[0];
                                String userB = "user_" + parts[1];

                                // Recipient é quem NÃO é o sender
                                recipientId = event.getSenderId().equals(userA) ? userB : userA;
                                System.out.println("[DEBUG] Extracted userA: " + userA);
                                System.out.println("[DEBUG] Extracted userB: " + userB);
                                System.out.println("[DEBUG] Sender is: " + event.getSenderId());
                                System.out.println("[DEBUG] Extracted recipient: " + recipientId);
                            } else {
                                System.out.println("[WARN] Could not parse conversation_id - unexpected format: " + conversationId);
                            }
                        } else {
                            System.out.println("[WARN] direct conversation_id missing user_ prefix: " + conversationId);
                    }
                } else if (conversationId.startsWith("group_")) {
                    // Para grupos, notificações serão enviadas depois na seção [6]
                    // Aqui apenas marcamos como grupo para referência
                    System.out.println("[DEBUG] Message for group: " + conversationId);
                    // Marcar que precisa notificar grupo (será feito na seção [6])
                    recipientId = "GROUP"; // Flag especial para indicar que é grupo
                }
                
                if (recipientId == null || recipientId.isEmpty()) {
                    System.out.println("[WARN] Could not determine recipient_id - skipping notification");
                }
            } else {
                System.out.println("[DEBUG] Using recipient_id from event: " + recipientId);
            }
            
            if (connectorRouter != null && connectorRouter.shouldRouteToConnector(recipientId)) {
                // Route to external connector (WhatsApp, Instagram, etc.)
                boolean routed = connectorRouter.routeToConnector(event);
                if (routed) {
                    System.out.println("✓ [2/2] Routed to external connector for recipient: " + recipientId);
                    System.out.println("✓ Processing complete for message: " + messageId + " (routed to connector)");
                    long duration = System.currentTimeMillis() - startTime;
                    metricsRegistry.recordMessageProcessed("ROUTED", duration);
                    return true;
                } else {
                    System.err.println("⚠ Warning: Failed to route to connector, falling back to local delivery");
                    // Fall through to local delivery
                }
            }
            
            // [4] LOCAL DELIVERY - Simular latência de entrega local
            // Em produção real: chamar API do serviço de push, SMS, etc.
            simulateDelivery(messageId);
            
            System.out.println("✓ [2/2] Simulated delivery");
            
            // [5] UPDATE STATUS - Marcar como DELIVERED
            cassandraStart = System.currentTimeMillis();
            boolean updated = messageStore.updateMessageStatus(
                messageId, 
                entity.getConversationId(), 
                entity.getTimestamp(), 
                "DELIVERED"
            );
            cassandraDuration = System.currentTimeMillis() - cassandraStart;
            metricsRegistry.recordCassandraWrite(cassandraDuration, updated);
            
            if (!updated) {
                System.err.println("⚠ Warning: Failed to update status to DELIVERED for " + messageId);
                // Não falhar todo o processamento por isso (eventual consistency)
            } else {
                System.out.println("✓ Status updated to DELIVERED");
            }
            
            // [6] PUBLISH NOTIFICATION - Notificar via Redis para WebSocket Gateway
            if (notificationPublisher != null) {
                // Caso 1: Mensagem 1:1 - notificar o recipientId
                if (recipientId != null && !recipientId.isEmpty() && !recipientId.equals("GROUP")) {
                    String senderUsername = messageStore.getUsername(event.getSenderId());
                    notificationPublisher.publishNewMessageNotification(
                        recipientId,
                        messageId,
                        event.getSenderId(),
                        senderUsername,
                        conversationId,
                        event.getContent(),
                        event.getFileId(),
                        null  // Sem groupName para diretas
                    );
                    System.out.println("✓ Notification published to Redis for user: " + recipientId);
                }
                
                // Caso 2: Mensagem de grupo - notificar todos os membros
                if (conversationId.startsWith("group_")) {
                    System.out.println("[DEBUG] Detected group message for: " + conversationId);
                    String groupId = conversationId;
                    java.util.List<String> groupMembers = messageStore.getGroupMembers(groupId);
                    
                    System.out.println("[DEBUG] getGroupMembers returned: " + groupMembers);
                    
                    if (groupMembers != null && !groupMembers.isEmpty()) {
                        System.out.println("[DEBUG] Publishing group notifications to " + groupMembers.size() + " members");
                        
                        String senderUsername = messageStore.getUsername(event.getSenderId());
                        String groupName = messageStore.getGroupName(groupId);
                        
                        for (String memberId : groupMembers) {
                            if (!memberId.equals(event.getSenderId())) {  // Não notificar o sender
                                System.out.println("  → Publishing to group member: " + memberId);
                                notificationPublisher.publishNewMessageNotification(
                                    memberId,
                                    messageId,
                                    event.getSenderId(),
                                    senderUsername,
                                    conversationId,
                                    event.getContent(),
                                    event.getFileId(),
                                    groupName  // Incluir nome do grupo
                                );
                            }
                        }
                        System.out.println("✓ Notifications published to all group members");
                    } else {
                        System.out.println("[WARN] Could not find group members for " + groupId);
                    }
                }
            }
            
            System.out.println("✓ Processing complete for message: " + messageId);
            long duration = System.currentTimeMillis() - startTime;
            metricsRegistry.recordMessageProcessed("DELIVERED", duration);
            return true;
            
        } catch (RuntimeException e) {
            System.err.println("✗ Error processing message " + messageId + ": " + e.getMessage());
            // Record failure metrics
            long duration = System.currentTimeMillis() - startTime;
            metricsRegistry.recordMessageFailed("runtime_error");
            metricsRegistry.recordMessageProcessed("FAILED", duration);
            // Exception causa Kafka retry (não commita offset)
            throw e;
        } catch (Exception e) {
            System.err.println("✗ Error processing message " + messageId + ": " + e.getMessage());
            // Record failure metrics
            long duration = System.currentTimeMillis() - startTime;
            metricsRegistry.recordMessageFailed("processing_error");
            metricsRegistry.recordMessageProcessed("FAILED", duration);
            // Exception causa Kafka retry (não commita offset)
            throw new RuntimeException("Processing failed for message " + messageId, e);
        }
    }
    
    /**
     * Simula entrega da mensagem ao destinatário
     * 
     * EDUCATIONAL PURPOSE: Placeholder for Real Delivery
     * 
     * EM PRODUÇÃO REAL, ISSO SERIA:
     * - Enviar push notification (Firebase, APNs)
     * - Enviar SMS (Twilio, AWS SNS)
     * - Enviar email
     * - Chamar webhook externo
     * - Publicar evento em outro sistema
     * 
     * PARA ESTE PROJETO DIDÁTICO:
     * - Sleep 100ms (simula latência de rede)
     * - Permite demonstrar status transitions (SENT → DELIVERED)
     * 
     * POR QUE NÃO IMPLEMENTAR DELIVERY REAL?
     * - Foco educacional: arquitetura distribuída, não integrações
     * - Simplifica deployment (sem dependências externas)
     * - Estudante pode implementar depois como exercício
     * 
     * LATÊNCIA REALISTA:
     * - Push notification: 50-200ms
     * - SMS: 500-2000ms
     * - Email: 100-500ms
     * - Webhook HTTP: 100-300ms
     * 
     * @param messageId ID da mensagem sendo entregue
     */
    private void simulateDelivery(String messageId) {
        try {
            // Simula latência de entrega (100ms)
            Thread.sleep(100);
            
            // Em produção: log structured com trace_id
            System.out.println("  → Delivered message " + messageId + " (simulated)");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("⚠ Delivery simulation interrupted for " + messageId);
        }
    }
}
