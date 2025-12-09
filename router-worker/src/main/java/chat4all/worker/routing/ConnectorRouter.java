package chat4all.worker.routing;

import chat4all.shared.MessageEvent;
import chat4all.shared.connector.ConnectorFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * ConnectorRouter - Routes messages to external platform connectors
 * 
 * REFACTORED - Now uses ConnectorFactory pattern
 * 
 * BEFORE (if/else chain, 50+ lines):
 * - determineTargetTopic() had hardcoded platform mappings
 * - Adding new connector required modifying this class
 * - Open/Closed Principle violated
 * - Tight coupling to specific connector implementations
 * 
 * AFTER (Factory pattern):
 * - Uses ConnectorFactory.getPlatformFromRecipientId()
 * - New connectors auto-registered in factory
 * - No changes needed to router when adding connectors
 * - Open/Closed Principle satisfied
 * 
 * Purpose:
 * - Determines which connector should handle a message based on recipient_id
 * - Publishes messages to platform-specific Kafka topics
 * 
 * Routing Rules:
 * - recipient_id starts with "whatsapp:" → publish to "whatsapp-outbound"
 * - recipient_id starts with "instagram:" → publish to "instagram-outbound"
 * - Otherwise → handle locally (internal delivery)
 * 
 * Example recipient IDs:
 * - "whatsapp:+5511999999999" → WhatsApp connector
 * - "instagram:@maria_silva" → Instagram connector
 * - "user_123" → Local delivery (internal user)
 * 
 * Educational Notes:
 * - Plugin pattern: easy to add new connectors without changing core logic
 * - Decoupling: connectors can scale independently
 * - Fault tolerance: if connector is down, messages wait in Kafka
 * 
 * @author Chat4All Team
 * @version 2.0.0 (Refactored with Factory)
 */
public class ConnectorRouter {
    
    private final KafkaProducer<String, String> producer;
    
    /**
     * Constructor
     * 
     * @param kafkaBootstrapServers Kafka broker addresses
     */
    public ConnectorRouter(String kafkaBootstrapServers) {
        // Configure Kafka producer for connector topics
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1"); // Leader acknowledgment
        props.put(ProducerConfig.RETRIES_CONFIG, "3"); // Retry on failure
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "router-worker-connector-publisher");
        
        this.producer = new KafkaProducer<>(props);
        
        System.out.println("[ConnectorRouter] Initialized");
    }
    
    /**
     * Check if message should be routed to external connector
     * 
     * @param recipientId Recipient identifier (e.g., "whatsapp:+5511999999999")
     * @return true if should route to connector, false for local delivery
     */
    public boolean shouldRouteToConnector(String recipientId) {
        if (recipientId == null || recipientId.isEmpty()) {
            return false;
        }
        
        // Check if recipient_id has platform prefix
        return recipientId.startsWith("whatsapp:") || 
               recipientId.startsWith("instagram:");
    }
    
    /**
     * Route message to appropriate connector
     * 
     * Educational Notes:
     * - Uses conversation_id as Kafka key for partitioning
     * - Fire-and-forget: doesn't wait for acknowledgment
     * - Connector will consume message and handle delivery
     * 
     * @param event MessageEvent to route
     * @return true if routed successfully, false otherwise
     */
    public boolean routeToConnector(MessageEvent event) {
        try {
            String recipientId = extractRecipientId(event);
            if (recipientId == null) {
                System.err.println("[ConnectorRouter] ❌ No recipient_id found in event");
                return false;
            }
            
            // Determine target topic based on recipient_id prefix
            String targetTopic = determineTargetTopic(recipientId);
            if (targetTopic == null) {
                System.err.println("[ConnectorRouter] ❌ Unknown connector for: " + recipientId);
                return false;
            }
            
            // Serialize event to JSON
            String eventJson = event.toJson();
            
            // Create producer record
            ProducerRecord<String, String> record = new ProducerRecord<>(
                targetTopic,
                event.getConversationId(), // Key: for partitioning
                eventJson                   // Value: JSON event
            );
            
            // Send asynchronously
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("[ConnectorRouter] ❌ Failed to route to " + targetTopic + ": " + exception.getMessage());
                } else {
                    System.out.println("[ConnectorRouter] ✓ Routed to " + targetTopic + 
                                     " (partition: " + metadata.partition() + 
                                     ", offset: " + metadata.offset() + ")");
                }
            });
            
            System.out.println("[ConnectorRouter] → Routing to " + targetTopic + " for " + recipientId);
            return true;
            
        } catch (Exception e) {
            System.err.println("[ConnectorRouter] ❌ Error routing message: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Extract recipient ID from MessageEvent
     * 
     * Phase 5: Now uses recipient_id field from MessageEvent.
     * Falls back to sender_id for backward compatibility.
     * 
     * @param event MessageEvent
     * @return recipient ID
     */
    private String extractRecipientId(MessageEvent event) {
        // Phase 5: Use recipient_id field if present
        String recipientId = event.getRecipientId();
        if (recipientId != null && !recipientId.isEmpty()) {
            return recipientId;
        }
        
        // Fallback to sender_id for backward compatibility
        // (used when recipient_id is not specified)
        return event.getSenderId();
    }
    
    /**
     * Determine target Kafka topic based on recipient ID prefix
     * 
     * REFACTORED: Now uses ConnectorFactory to extract platform
     * This eliminates hardcoded if/else chain and allows dynamic connector registration
     * 
     * @param recipientId Recipient identifier
     * @return Target topic name, or null if unknown
     */
    private String determineTargetTopic(String recipientId) {
        try {
            // Extract platform from recipient_id using factory (e.g., "whatsapp:+55..." → "whatsapp")
            String platform = ConnectorFactory.getPlatformFromRecipientId(recipientId);
            
            if (platform == null || platform.isEmpty()) {
                return null; // Unknown platform
            }
            
            // Convert platform to topic name: "whatsapp" → "whatsapp-outbound"
            return platform + "-outbound";
            
        } catch (Exception e) {
            System.err.println("[ConnectorRouter] Error determining topic for: " + recipientId);
            return null;
        }
    }
    
    /**
     * Close the router and release resources
     */
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
            System.out.println("[ConnectorRouter] Closed");
        }
    }
}
