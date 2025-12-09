package chat4all.connector.instagram;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Status Publisher - Publishes delivery/read status updates to Kafka
 * 
 * Purpose:
 * - Publishes status events to "status-updates" Kafka topic
 * - Status consumer (in router-worker) updates message status in Cassandra
 * 
 * Event Structure:
 * {
 *   "message_id": "msg_abc123...",
 *   "status": "DELIVERED",
 *   "timestamp": 1234567890123,
 *   "connector": "instagram"
 * }
 * 
 * Status Flow:
 * SENT (router-worker) → DELIVERED (connector) → READ (connector/user)
 * 
 * Educational Notes:
 * - Fire-and-forget pattern: no need to wait for acknowledgment
 * - Idempotent: same status update can be sent multiple times safely
 * - Kafka ensures delivery (retries, acknowledgments)
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class StatusPublisher {
    
    private final KafkaProducer<String, String> producer;
    private final String statusTopic;
    
    /**
     * Constructor
     * 
     * @param kafkaBootstrapServers Kafka broker addresses
     * @param statusTopic Topic for publishing status updates
     */
    public StatusPublisher(String kafkaBootstrapServers, String statusTopic) {
        this.statusTopic = statusTopic;
        
        // Configure Kafka producer
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1"); // Leader acknowledgment
        props.put(ProducerConfig.RETRIES_CONFIG, "3"); // Retry on failure
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "instagram-connector-status-publisher");
        
        this.producer = new KafkaProducer<>(props);
        
        System.out.println("✅ StatusPublisher initialized");
        System.out.println("   Publishing to topic: " + statusTopic);
    }
    
    /**
     * Publish DELIVERED status for a message
     * 
     * Educational Notes:
     * - Uses message_id as Kafka key for partitioning
     * - Same message_id always goes to same partition (ordering guaranteed)
     * - Fire-and-forget: doesn't wait for acknowledgment
     * 
     * @param messageId Message ID that was delivered
     */
    public void publishDelivered(String messageId) {
        try {
            // Build JSON event (manual serialization for educational clarity)
            String event = buildStatusEvent(messageId, "DELIVERED");
            
            // Create producer record (key = message_id, value = JSON event)
            ProducerRecord<String, String> record = new ProducerRecord<>(
                statusTopic,
                messageId, // Key: ensures same message goes to same partition
                event      // Value: JSON event payload
            );
            
            // Send asynchronously (fire-and-forget)
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("❌ Failed to publish status update: " + exception.getMessage());
                } else {
                    System.out.println("[Status] Published DELIVERED for " + messageId + 
                                     " (partition: " + metadata.partition() + 
                                     ", offset: " + metadata.offset() + ")");
                }
            });
            
        } catch (Exception e) {
            System.err.println("❌ Error publishing status: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Build status event JSON
     * 
     * Educational Note:
     * - Manual JSON serialization (no Jackson/Gson dependency)
     * - Shows exact event structure for learning purposes
     * 
     * @param messageId Message ID
     * @param status Status value (DELIVERED, READ)
     * @return JSON string
     */
    private String buildStatusEvent(String messageId, String status) {
        long timestamp = System.currentTimeMillis();
        
        return String.format(
            "{\"message_id\":\"%s\",\"status\":\"%s\",\"timestamp\":%d,\"connector\":\"instagram\"}",
            escapeJson(messageId),
            escapeJson(status),
            timestamp
        );
    }
    
    /**
     * Escape special characters in JSON strings
     * 
     * @param str String to escape
     * @return Escaped string
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * Publish READ status for a message
     * 
     * Educational Notes:
     * - Simulates user reading the message (Phase 2 requirement)
     * - Uses message_id as Kafka key for partitioning
     * - Fire-and-forget: doesn't wait for acknowledgment
     * 
     * @param messageId Message ID that was read
     */
    public void publishRead(String messageId) {
        try {
            // Build JSON event (manual serialization for educational clarity)
            String event = buildStatusEvent(messageId, "READ");
            
            // Create producer record (key = message_id, value = JSON event)
            ProducerRecord<String, String> record = new ProducerRecord<>(
                statusTopic,
                messageId, // Key: ensures same message goes to same partition
                event      // Value: JSON event payload
            );
            
            // Send asynchronously (fire-and-forget)
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("❌ Failed to publish status update: " + exception.getMessage());
                } else {
                    System.out.println("[Status] Published READ for " + messageId + 
                                     " (partition: " + metadata.partition() + 
                                     ", offset: " + metadata.offset() + ")");
                }
            });
            
        } catch (Exception e) {
            System.err.println("❌ Error publishing status: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Close the producer and release resources
     * 
     * Called during shutdown
     */
    public void close() {
        if (producer != null) {
            producer.flush(); // Wait for pending sends
            producer.close();
            System.out.println("✅ StatusPublisher closed");
        }
    }
}
