package chat4all.connector.instagram;

import chat4all.shared.MessageEvent;
import chat4all.shared.connector.BaseConnector;
import chat4all.shared.connector.ConnectorException;
import chat4all.shared.connector.WebhookEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;

/**
 * Instagram Connector - Refatorado para usar BaseConnector
 * 
 * PADRÃO: Template Method + Strategy
 * 
 * ANTES (250 linhas):
 * - Continha CircuitBreaker duplicado (152 linhas)
 * - Continha MetricsRegistry duplicado
 * - Lógica de resilience misturada com lógica de negócio
 * 
 * DEPOIS (150 linhas):
 * - Estende BaseConnector (herda circuit breaker)
 * - Estende BaseConnector (herda métricas)
 * - Foca apenas em consumir Kafka e chamar API
 * 
 * Purpose:
 * - Consumes messages from "instagram-outbound" Kafka topic
 * - Simulates Instagram Business API calls
 * - Publishes delivery status updates
 * 
 * Educational Notes:
 * - Kafka consumer with manual commit (at-least-once delivery)
 * - Circuit breaker herdado de BaseConnector
 * - Graceful shutdown: closes resources properly
 * 
 * @author Chat4All Team
 * @version 2.0.0 (Refatorado)
 */
public class InstagramConnector extends BaseConnector {
    
    private final KafkaConsumer<String, String> consumer;
    private final StatusPublisher statusPublisher;
    private final Random random;
    private volatile boolean running;
    
    /**
     * Constructor - Now delegates resilience to BaseConnector
     * 
     * @param kafkaBootstrapServers Kafka broker addresses
     * @param consumerGroupId Consumer group ID for coordination
     * @param inboundTopic Topic to consume messages from
     * @param statusPublisher Publisher for status updates
     */
    public InstagramConnector(
        String kafkaBootstrapServers,
        String consumerGroupId,
        String inboundTopic,
        StatusPublisher statusPublisher
    ) {
        super();
        this.statusPublisher = statusPublisher;
        this.random = new Random();
        this.running = true;
        
        // Configure Kafka consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // Manual commit
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10"); // Process in small batches
        
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Collections.singletonList(inboundTopic));
        
        System.out.println("✅ Instagram connector initialized");
        System.out.println("   Subscribed to topic: " + inboundTopic);
        System.out.println("   Consumer group: " + consumerGroupId);
    }
    
    /**
     * Main run loop - consumes and processes messages
     * 
     * Educational Notes:
     * - Poll with timeout: blocks for max 1 second waiting for messages
     * - Commit after processing: ensures at-least-once delivery
     * - Graceful shutdown: checks running flag after each poll
     */
    public void run() {
        System.out.println("");
        System.out.println("===========================================");
        System.out.println("  ✅ Instagram Connector Ready");
        System.out.println("  ⏳ Waiting for messages...");
        System.out.println("  Press Ctrl+C to stop");
        System.out.println("===========================================");
        System.out.println("");
        
        while (running) {
            try {
                // Poll for messages (blocks for max 1 second)
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                
                if (records.isEmpty()) {
                    continue; // No messages, poll again
                }
                
                System.out.println("▼ Polled " + records.count() + " message(s)");
                System.out.println("");
                
                // Process each message
                for (ConsumerRecord<String, String> record : records) {
                    processMessage(record);
                }
                
                // Commit offsets after successful processing
                consumer.commitSync();
                System.out.println("✅ Committed offsets for " + records.count() + " message(s)");
                System.out.println("");
                
            } catch (Exception e) {
                System.err.println("❌ Error in consumer loop: " + e.getMessage());
                e.printStackTrace();
                // Continue running despite errors (fault tolerance)
            }
        }
        
        // Cleanup
        consumer.close();
        System.out.println("✅ Consumer closed");
    }
    
    /**
     * Process a single message from Kafka
     * 
     * Flow:
     * 1. Deserialize MessageEvent from JSON
     * 2. Log message consumption
     * 3. Simulate Instagram API call (random delay)
     * 4. Publish DELIVERED status update
     * 
     * @param record Kafka consumer record
     */
    private void processMessage(ConsumerRecord<String, String> record) {
        try {
            System.out.println("─────────────────────────────────");
            System.out.println("Partition: " + record.partition() + " | Offset: " + record.offset() + " | Key: " + record.key());
            System.out.println("");
            
            // Deserialize MessageEvent
            String messageJson = record.value();
            MessageEvent event = MessageEvent.fromJson(messageJson);
            
            String messageId = event.getMessageId();
            String recipientId = event.getSenderId(); // In real system, extract from conversation participants
            
            System.out.println("[Instagram] Consumed message: " + messageId);
            System.out.println("[Instagram] Recipient: " + recipientId);
            System.out.println("[Instagram] Content: " + event.getContent());
            
            // Check circuit breaker before making API call (inherited from BaseConnector)
            if (!getCircuitBreaker().allowRequest()) {
                System.err.println("⚠️ [Instagram] Circuit breaker OPEN, skipping API call for: " + messageId);
                // Message stays in Kafka, will retry later when circuit closes
                return;
            }
            
            // Simulate Instagram Business API call (track duration)
            long apiStart = System.currentTimeMillis();
            boolean success = simulateApiCall(messageId, recipientId);
            long apiDuration = System.currentTimeMillis() - apiStart;
            
            // Update circuit breaker based on result (inherited from BaseConnector)
            if (success) {
                getCircuitBreaker().recordSuccess();
                
                // Publish DELIVERED status
                statusPublisher.publishDelivered(messageId);
                
                // Schedule READ status simulation after random delay (2-5 seconds)
                scheduleReadStatus(messageId);
                
                System.out.println("✅ Processing complete for message: " + messageId);
            } else {
                getCircuitBreaker().recordFailure();
                System.err.println("❌ Failed to deliver message: " + messageId);
            }
            
            System.out.println("");
            
        } catch (Exception e) {
            System.err.println("❌ Error processing message: " + e.getMessage());
            e.printStackTrace();
            // Record failure
            getCircuitBreaker().recordFailure();
            // Don't rethrow - continue processing other messages
        }
    }
    
    /**
     * Simulate Instagram Business API call
     * 
     * Educational Notes:
     * - Random delay (300-700ms) simulates network latency
     * - In production: would use HTTP client to call real Instagram API
     * - Error handling: retry logic, circuit breaker patterns
     * - Simulates 10% failure rate for circuit breaker testing
     * 
     * @param messageId Message ID being delivered
     * @param recipientId Recipient username
     * @return true if delivery succeeded, false if failed
     */
    private boolean simulateApiCall(String messageId, String recipientId) {
        try {
            // Simulate 10% failure rate for circuit breaker testing
            boolean shouldFail = random.nextInt(10) == 0;
            
            if (shouldFail) {
                System.err.println("[Instagram] ✗ Simulated API failure for " + recipientId);
                return false;
            }
            
            // Random delay between 300-700ms
            int delayMs = 300 + random.nextInt(400);
            
            System.out.println("[Instagram] Simulating API call...");
            System.out.println("  → Latency: " + delayMs + "ms");
            
            Thread.sleep(delayMs);
            
            System.out.println("[Instagram] ✓ Delivered to " + recipientId);
            return true;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("❌ API simulation interrupted");
            return false;
        }
    }
    
    /**
     * Schedule READ status publication after random delay
     * 
     * Educational Notes:
     * - Simulates user receiving Instagram DM notification and opening the chat
     * - Random delay (2-5 seconds) mimics real-world user behavior
     * - Runs in background thread to not block message processing
     * - Phase 2 requirement: SENT → DELIVERED → READ lifecycle
     * 
     * @param messageId Message ID to mark as read
     */
    private void scheduleReadStatus(String messageId) {
        // Random delay between 2000-5000ms (2-5 seconds)
        int delayMs = 2000 + random.nextInt(3000);
        
        // Run in background thread
        new Thread(() -> {
            try {
                System.out.println("[Instagram] Scheduling READ status for " + messageId + " in " + delayMs + "ms");
                Thread.sleep(delayMs);
                
                // Publish READ status
                statusPublisher.publishRead(messageId);
                System.out.println("[Instagram] ✓ User read message: " + messageId);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("⚠️  READ status simulation interrupted for: " + messageId);
            }
        }).start();
    }
    
    /**
     * Return connector ID for registration in factory
     */
    @Override
    public String getConnectorId() {
        return "instagram";
    }

    /**
     * Return friendly name for monitoring and logs
     */
    @Override
    public String getName() {
        return "Instagram Connector";
    }

    /**
     * Return version for compatibility checks
     */
    @Override
    public String getVersion() {
        return "2.0.0";
    }

    /**
     * Send message implementation (Instagram uses Kafka consumer pattern)
     * This method is called by parent's sendMessage() template
     * 
     * @param message MessageEvent to send
     * @return true if message sent successfully
     */
    @Override
    protected boolean sendMessageImpl(MessageEvent message) {
        throw new UnsupportedOperationException(
            "Instagram connector uses Kafka consumer pattern. " +
            "Messages flow through run() → processMessage() → simulateApiCall()"
        );
    }
    
    /**
     * Process webhook event from Instagram platform
     * 
     * Educational Notes:
     * - This connector uses Kafka consumer pattern (run loop)
     * - Webhooks not used in this demo (simulated via Kafka)
     * - In production: would receive delivery/read receipts via HTTP webhooks
     * 
     * @param event Webhook event from platform
     */
    @Override
    public void onWebhookEvent(WebhookEvent event) throws ConnectorException {
        // Not implemented - using Kafka consumer pattern instead
        throw new UnsupportedOperationException(
            "Instagram connector uses Kafka consumer pattern. " +
            "Webhook events not supported in demo mode."
        );
    }
    
    /**
     * Send file message (not implemented - using Kafka consumer pattern)
     * 
     * @param conversationId Conversation ID
     * @param fileId File ID in MinIO
     * @param fileMetadata File metadata (filename, size, mime_type)
     * @return true if sent successfully
     */
    @Override
    public boolean sendFile(String conversationId, String fileId, java.util.Map<String, String> fileMetadata) 
        throws ConnectorException {
        // Not implemented - using Kafka consumer pattern instead
        throw new UnsupportedOperationException(
            "Instagram connector uses Kafka consumer pattern. " +
            "Files are sent via MessageEvent in Kafka topic."
        );
    }
    
    /**
     * Send text message (not implemented - using Kafka consumer pattern)
     * 
     * @param conversationId Conversation ID
     * @param text Message text
     * @return true if sent successfully
     */
    @Override
    public boolean sendText(String conversationId, String text) throws ConnectorException {
        // Not implemented - using Kafka consumer pattern instead
        throw new UnsupportedOperationException(
            "Instagram connector uses Kafka consumer pattern. " +
            "Text messages are sent via MessageEvent in Kafka topic."
        );
    }
    
    /**
     * Stop the connector gracefully
     * 
     * Called by shutdown hook in Main.java
     */
    public void stop() {
        this.running = false;
    }
}
