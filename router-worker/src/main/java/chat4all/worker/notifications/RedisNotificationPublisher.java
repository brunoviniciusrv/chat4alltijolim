package chat4all.worker.notifications;

import org.json.JSONObject;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * RedisNotificationPublisher - Publica notificações no Redis Pub/Sub
 * 
 * PROPÓSITO: Real-time notification delivery via WebSocket
 * ==================
 * 
 * ARQUITETURA:
 * ```
 * Router Worker (aqui) → Redis Pub/Sub → WebSocket Gateway → Cliente
 * ```
 * 
 * FLUXO:
 * 1. Router Worker processa mensagem e persiste no Cassandra
 * 2. Router Worker publica notificação no Redis: notifications:{userId}
 * 3. WebSocket Gateway (subscrito ao Redis) recebe notificação
 * 4. WebSocket Gateway envia para cliente conectado via WebSocket
 * 
 * ESCALABILIDADE:
 * - Redis Pub/Sub: broadcast para múltiplas instâncias de WebSocket Gateway
 * - Stateless: qualquer worker pode publicar, qualquer gateway pode consumir
 * - Latência < 10ms (Redis in-memory)
 * 
 * POR QUE REDIS PUB/SUB?
 * - Ultra-baixa latência (in-memory)
 * - Broadcast nativo (1 publish → N subscribers)
 * - Simples e confiável
 * - Não precisa persistência (fire-and-forget)
 * 
 * CHANNEL NAMING:
 * - Pattern: notifications:{userId}
 * - Exemplo: notifications:user123
 * - WebSocket Gateway subscreve ao pattern: notifications:*
 * 
 * @author Chat4All Educational Project
 */
public class RedisNotificationPublisher {
    
    private final JedisPool jedisPool;
    
    public RedisNotificationPublisher(String redisHost, int redisPort) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        
        this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort);
        System.out.println("✓ Redis publisher initialized: " + redisHost + ":" + redisPort);
    }
    
    /**
     * Publica notificação de nova mensagem no Redis
     * 
     * @param recipientUserId ID do usuário que deve receber notificação
     * @param messageId ID da mensagem
     * @param senderId ID do remetente
     * @param conversationId ID da conversa
     * @param content Conteúdo da mensagem (preview)
     * @param fileId ID do arquivo anexado (pode ser null)
     */
    public void publishNewMessageNotification(
        String recipientUserId,
        String messageId,
        String senderId,
        String senderUsername,
        String conversationId,
        String content,
        String fileId,
        String groupName
    ) {
        try (Jedis jedis = jedisPool.getResource()) {
            // Criar payload JSON
            JSONObject notification = new JSONObject();
            notification.put("type", "new_message");
            notification.put("message_id", messageId);
            notification.put("sender_id", senderId);
            if (senderUsername != null && !senderUsername.isEmpty()) {
                notification.put("sender_username", senderUsername);
            }
            notification.put("conversation_id", conversationId);
            notification.put("content", content);
            notification.put("timestamp", System.currentTimeMillis());

            if (groupName != null && !groupName.isEmpty()) {
                notification.put("group_name", groupName);
            }
            
            if (fileId != null && !fileId.isEmpty()) {
                notification.put("file_id", fileId);
            }
            
            // Publicar no channel específico do usuário
            String channel = "notifications:" + recipientUserId;
            long subscribers = jedis.publish(channel, notification.toString());
            
            System.out.println("✓ Published notification to Redis channel: " + channel + 
                             " (subscribers: " + subscribers + ")");
            
        } catch (Exception e) {
            System.err.println("✗ Failed to publish notification to Redis: " + e.getMessage());
            // Não falhar processamento inteiro por falha de notificação
            // Usuário receberá mensagem no próximo polling (fallback)
        }
    }
    
    /**
     * Fecha pool de conexões Redis
     */
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            System.out.println("✓ Redis publisher closed");
        }
    }
}
