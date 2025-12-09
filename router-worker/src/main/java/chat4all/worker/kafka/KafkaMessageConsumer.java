package chat4all.worker.kafka;

import chat4all.shared.MessageEvent;
import chat4all.worker.processing.MessageProcessor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

/**
 * KafkaMessageConsumer - Consumidor Kafka para tópico "messages"
 * 
 * PROPÓSITO EDUCACIONAL: Event-Driven Consumer Pattern
 * ==================
 * 
 * O QUE É UM KAFKA CONSUMER?
 * - Cliente que lê mensagens de um tópico Kafka
 * - Pertence a um consumer group (coordenação entre workers)
 * - Recebe partitions atribuídas automaticamente (rebalancing)
 * 
 * CONSUMER GROUP PATTERN:
 * ```
 * Topic "messages" (3 partitions)
 *   Partition 0 → Worker 1 (consumer group: router-worker-group)
 *   Partition 1 → Worker 2 (consumer group: router-worker-group)
 *   Partition 2 → Worker 3 (consumer group: router-worker-group)
 * ```
 * 
 * HORIZONTAL SCALING:
 * - Adicionar mais workers = processar mais mensagens em paralelo
 * - Kafka rebalanceia partitions automaticamente
 * - Max workers úteis = número de partitions (3 neste projeto)
 * 
 * OFFSET MANAGEMENT:
 * - Offset: posição atual no log de cada partition
 * - Manual commit: commitamos DEPOIS de processar com sucesso
 * - Se crashar antes do commit: Kafka reenvia (at-least-once)
 * 
 * AT-LEAST-ONCE DELIVERY:
 * - Garantia: mensagem processada pelo menos 1 vez
 * - Trade-off: pode processar 2x se crashar antes de commitar
 * - Solução: Idempotência (message_id deduplication)
 * 
 * @author Chat4All Educational Project
 */
public class KafkaMessageConsumer {
    
    private final KafkaConsumer<String, String> consumer;
    private final MessageProcessor processor;
    private final String topic;
    private volatile boolean running = true;
    
    /**
     * Cria KafkaMessageConsumer
     * 
     * CONFIGURAÇÃO:
     * - bootstrap.servers: Lista de brokers Kafka
     * - group.id: Consumer group (coordenação entre workers)
     * - enable.auto.commit: false (manual commit para confiabilidade)
     * - auto.offset.reset: earliest (consumir desde início se novo consumer)
     * 
     * EDUCATIONAL NOTE: Auto-commit vs Manual-commit
     * 
     * AUTO-COMMIT (evitar):
     * ```
     * poll() → auto-commit offset → process()
     *                                   ↓ (crash aqui)
     *                              Mensagem perdida! (offset já commitado)
     * ```
     * 
     * MANUAL-COMMIT (recomendado):
     * ```
     * poll() → process() → commit offset
     *             ↓ (crash aqui)
     *         Kafka reenvia (offset não commitado)
     *         Deduplicação previne duplicação
     * ```
     * 
     * @param bootstrapServers Kafka brokers (e.g., "kafka:9092")
     * @param topic Tópico a consumir (e.g., "messages")
     * @param groupId Consumer group ID
     * @param processor Processador de mensagens
     */
    public KafkaMessageConsumer(String bootstrapServers, String topic, String groupId, MessageProcessor processor) {
        this.topic = topic;
        this.processor = processor;
        
        // Configuração do consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        
        // CRÍTICO: Desabilitar auto-commit
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        
        // Se novo consumer: começar do início do tópico
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Max poll interval: 5 minutos (tempo máximo para processar batch)
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "300000");
        
        // Max poll records: 10 mensagens por poll (batch size)
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");
        
        this.consumer = new KafkaConsumer<>(props);
        
        System.out.println("✓ KafkaMessageConsumer created:");
        System.out.println("  Bootstrap servers: " + bootstrapServers);
        System.out.println("  Topic: " + topic);
        System.out.println("  Group ID: " + groupId);
        System.out.println("  Auto-commit: DISABLED (manual commit)");
    }
    
    /**
     * Inicia consumo de mensagens (loop infinito)
     * 
     * FLUXO DO CONSUMER LOOP:
     * ```
     * while (running) {
     *   [1] poll() → busca mensagens do Kafka (max 10 por vez)
     *   [2] Para cada mensagem:
     *       - Deserializar JSON → MessageEvent
     *       - Chamar processor.process()
     *       - Se sucesso: continuar
     *       - Se erro: throw exception (não commita)
     *   [3] commitSync() → confirma processamento ao Kafka
     * }
     * ```
     * 
     * EDUCATIONAL NOTE: Poll Model
     * - Consumer PUXA mensagens (poll), não Kafka empurra
     * - Poll com timeout: aguarda até 1s por novas mensagens
     * - Se sem mensagens: retorna vazio, loop continua
     * 
     * REBALANCING:
     * - Se novo worker entra: Kafka redistribui partitions
     * - Se worker sai: Kafka reatribui suas partitions aos sobreviventes
     * - Durante rebalance: consumo para (alguns segundos)
     * 
     * GRACEFUL SHUTDOWN:
     * - running = false → sai do loop
     * - Fecha consumer → commita offsets finais
     * - Permite Kafka reatribuir partitions rapidamente
     */
    public void startConsuming() {
        try {
            // Subscribe ao tópico
            consumer.subscribe(Collections.singletonList(topic));
            System.out.println("✓ Subscribed to topic: " + topic);
            System.out.println("▶ Starting consumer loop...\n");
            
            // Loop infinito de consumo
            while (running) {
                // [1] POLL - Busca mensagens do Kafka (timeout: 1 segundo)
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                
                if (records.isEmpty()) {
                    // Sem mensagens novas, continuar aguardando
                    continue;
                }
                
                System.out.println("▼ Polled " + records.count() + " messages");
                
                // [2] PROCESS - Processar cada mensagem
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        // Log da mensagem recebida
                        System.out.println("\n─────────────────────────────────");
                        System.out.println("Partition: " + record.partition() + 
                                         " | Offset: " + record.offset() +
                                         " | Key: " + record.key());
                        
                        // Deserializar JSON → MessageEvent
                        MessageEvent event = MessageEvent.fromJson(record.value());
                        
                        // Processar mensagem
                        processor.process(event);
                        
                    } catch (Exception e) {
                        // Erro no processamento: log e RE-THROW
                        System.err.println("✗ Error processing record at offset " + record.offset());
                        e.printStackTrace();
                        
                        // Re-throw para não commitar offset (Kafka reenviará)
                        throw new RuntimeException("Processing failed", e);
                    }
                }
                
                // [3] COMMIT - Confirmar processamento ao Kafka
                // CRÍTICO: Só chega aqui se TODAS as mensagens do batch processaram
                try {
                    consumer.commitSync();
                    System.out.println("\n✓ Committed offsets for " + records.count() + " messages\n");
                    
                } catch (Exception e) {
                    System.err.println("✗ Failed to commit offsets: " + e.getMessage());
                    // Se commit falhar, Kafka reenviará mensagens (dedup protege)
                }
            }
            
        } catch (Exception e) {
            System.err.println("✗ Consumer loop error: " + e.getMessage());
            e.printStackTrace();
            
        } finally {
            // Cleanup: fechar consumer
            close();
        }
    }
    
    /**
     * Para o consumer gracefully
     * 
     * GRACEFUL SHUTDOWN:
     * - Define running = false
     * - Loop termina naturalmente na próxima iteração
     * - Consumer fecha conexões
     * - Kafka rebalanceia partitions para outros workers
     */
    public void stop() {
        System.out.println("\n⊗ Stopping consumer...");
        running = false;
    }
    
    /**
     * Fecha consumer e libera recursos
     */
    public void close() {
        if (consumer != null) {
            try {
                consumer.close(Duration.ofSeconds(10));
                System.out.println("✓ Consumer closed");
            } catch (Exception e) {
                System.err.println("⚠ Error closing consumer: " + e.getMessage());
            }
        }
    }
}
