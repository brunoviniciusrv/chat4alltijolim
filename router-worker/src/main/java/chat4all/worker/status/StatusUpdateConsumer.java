package chat4all.worker.status;

import chat4all.worker.cassandra.CassandraConnection;
import chat4all.shared.MessageStatus;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

/**
 * StatusUpdateConsumer - Processes delivery/read confirmations
 * 
 * PROPÓSITO EDUCACIONAL: Event-Driven Architecture + State Management
 * ==================
 * 
 * PURPOSE:
 * - Consumes status update events from "status-updates" Kafka topic
 * - Updates message status in Cassandra (SENT → DELIVERED → READ)
 * - Validates state transitions using MessageStatus state machine
 * - Records timestamps (delivered_at, read_at) for audit trail
 * 
 * ARCHITECTURE:
 * ```
 * Connector → Kafka(status-updates) → StatusUpdateConsumer → Cassandra
 *   |                                           |
 *   | {message_id, status: "DELIVERED"}         | UPDATE messages SET status=?, delivered_at=?
 *   └───────────────────────────────────────────┘
 * ```
 * 
 * EVENT FORMAT:
 * ```json
 * {
 *   "message_id": "msg_abc123",
 *   "status": "DELIVERED",
 *   "timestamp": 1700000000000
 * }
 * ```
 * 
 * STATE MACHINE:
 * - SENT → DELIVERED: Connector confirms delivery
 * - DELIVERED → READ: User marks as read
 * - SENT → READ: INVALID (rejected)
 * 
 * WHY SEPARATE CONSUMER?
 * - Decouples connectors from Cassandra (connectors don't need DB access)
 * - Centralizes status validation logic
 * - Enables retry on Cassandra failures (Kafka offset not committed)
 * - Scales independently (can add more consumer instances)
 * 
 * @author Chat4All Educational Project
 */
public class StatusUpdateConsumer {
    
    private final KafkaConsumer<String, String> consumer;
    private final CqlSession session;
    private final PreparedStatement selectStatement;
    private final PreparedStatement updateToDeliveredStatement;
    private final PreparedStatement updateToReadStatement;
    private volatile boolean running;
    
    /**
     * Constructor
     * 
     * @param kafkaBootstrapServers Kafka broker addresses
     * @param consumerGroupId Consumer group ID
     * @param statusTopic Status updates topic name
     * @param cassandraConnection Cassandra session
     */
    public StatusUpdateConsumer(
        String kafkaBootstrapServers,
        String consumerGroupId,
        String statusTopic,
        CassandraConnection cassandraConnection
    ) {
        this.session = cassandraConnection.getSession();
        this.running = true;
        
        // Prepare Cassandra statements
        // Educational note: Using ALLOW FILTERING because message_id is a secondary index
        // In production, consider using a separate table with message_id as partition key
        this.selectStatement = session.prepare(
            "SELECT conversation_id, timestamp, status FROM chat4all.messages WHERE message_id = ? ALLOW FILTERING"
        );
        
        this.updateToDeliveredStatement = session.prepare(
            "UPDATE chat4all.messages SET status = ?, delivered_at = ? " +
            "WHERE conversation_id = ? AND timestamp = ?"
        );
        
        this.updateToReadStatement = session.prepare(
            "UPDATE chat4all.messages SET status = ?, read_at = ? " +
            "WHERE conversation_id = ? AND timestamp = ?"
        );
        
        // Configure Kafka consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // Manual commit
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
        
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Collections.singletonList(statusTopic));
        
        System.out.println("✅ StatusUpdateConsumer initialized");
        System.out.println("   Topic: " + statusTopic);
        System.out.println("   Consumer group: " + consumerGroupId);
    }
    
    /**
     * Main run loop - consumes and processes status updates
     */
    public void run() {
        System.out.println("");
        System.out.println("===========================================");
        System.out.println("  ✅ Status Update Consumer Ready");
        System.out.println("  ⏳ Listening for status updates...");
        System.out.println("===========================================");
        System.out.println("");
        
        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                
                if (records.isEmpty()) {
                    continue;
                }
                
                System.out.println("▼ Received " + records.count() + " status update(s)");
                
                for (ConsumerRecord<String, String> record : records) {
                    processStatusUpdate(record);
                }
                
                // Commit offsets after processing batch
                consumer.commitSync();
                
            } catch (Exception e) {
                System.err.println("❌ Error processing status updates: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        consumer.close();
        System.out.println("StatusUpdateConsumer stopped");
    }
    
    /**
     * Process individual status update event
     * 
     * @param record Kafka record with status update
     */
    private void processStatusUpdate(ConsumerRecord<String, String> record) {
        try {
            // Parse JSON manually (educational: no Jackson)
            String json = record.value();
            Map<String, Object> event = parseJson(json);
            
            String messageId = (String) event.get("message_id");
            String newStatus = (String) event.get("status");
            Long timestamp = ((Number) event.get("timestamp")).longValue();
            
            if (messageId == null || newStatus == null) {
                System.err.println("⚠️  Invalid status update (missing fields): " + json);
                return;
            }
            
            System.out.println("📨 Status update: " + messageId + " → " + newStatus);
            
            // Update message status in Cassandra
            updateMessageStatus(messageId, newStatus, timestamp);
            
        } catch (Exception e) {
            System.err.println("❌ Failed to process status update: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Update message status in Cassandra with state machine validation
     * 
     * FLOW:
     * 1. Query current status from database (using ALLOW FILTERING on message_id)
     * 2. Validate transition using MessageStatus.isValidTransition()
     * 3. If valid, update status and timestamp using full primary key
     * 4. If invalid, log warning (idempotent, don't fail)
     * 
     * @param messageId Message ID to update
     * @param newStatusStr New status (DELIVERED or READ)
     * @param timestamp Event timestamp (millis since epoch)
     */
    private void updateMessageStatus(String messageId, String newStatusStr, long timestamp) {
        try {
            // 1. Query current status + primary key components
            // Educational note: ALLOW FILTERING required because message_id is not partition key
            ResultSet resultSet = session.execute(
                selectStatement.bind(messageId)
            );
            
            Row row = resultSet.one();
            if (row == null) {
                System.err.println("⚠️  Message not found: " + messageId);
                return;
            }
            
            String conversationId = row.getString("conversation_id");
            Instant messageTimestamp = row.getInstant("timestamp");
            String currentStatusStr = row.getString("status");
            
            MessageStatus currentStatus = MessageStatus.fromString(currentStatusStr);
            MessageStatus newStatus = MessageStatus.fromString(newStatusStr);
            
            // 2. Validate transition
            if (!MessageStatus.isValidTransition(currentStatus, newStatus)) {
                System.err.println("⚠️  Invalid transition: " + currentStatus + " → " + newStatus + 
                    " (message: " + messageId + ")");
                return;
            }
            
            // 3. Idempotency check (already in target state)
            if (currentStatus == newStatus) {
                System.out.println("✓ Already in " + newStatus + " state (idempotent): " + messageId);
                return;
            }
            
            // 4. Update status based on target state using full primary key
            // Educational note: Cassandra requires partition key + clustering key for updates
            Instant timestampInstant = Instant.ofEpochMilli(timestamp);
            
            if (newStatus == MessageStatus.DELIVERED) {
                session.execute(
                    updateToDeliveredStatement.bind(
                        newStatus.getValue(),
                        timestampInstant,
                        conversationId,
                        messageTimestamp
                    )
                );
                System.out.println("✓ Updated to DELIVERED: " + messageId);
                
            } else if (newStatus == MessageStatus.READ) {
                session.execute(
                    updateToReadStatement.bind(
                        newStatus.getValue(),
                        timestampInstant,
                        conversationId,
                        messageTimestamp
                    )
                );
                System.out.println("✓ Updated to READ: " + messageId);
            }
            
        } catch (Exception e) {
            System.err.println("❌ Failed to update status for " + messageId + ": " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Parse JSON string to Map (manual parsing for educational purposes)
     * 
     * PRODUCTION NOTE: Use Jackson/Gson for real projects
     * 
     * @param json JSON string
     * @return Map with parsed values
     */
    private Map<String, Object> parseJson(String json) {
        // Remove braces and split by comma
        json = json.trim().substring(1, json.length() - 1);
        
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        
        String[] pairs = json.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replace("\"", "");
                String value = keyValue[1].trim().replace("\"", "");
                
                // Try parse as number
                if (key.equals("timestamp")) {
                    map.put(key, Long.parseLong(value));
                } else {
                    map.put(key, value);
                }
            }
        }
        
        return map;
    }
    
    /**
     * Graceful shutdown
     */
    public void shutdown() {
        running = false;
    }
}
