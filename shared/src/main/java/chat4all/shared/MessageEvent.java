package chat4all.shared;

/**
 * MessageEvent - Kafka Event Schema for Chat4All
 * 
 * EDUCATIONAL PURPOSE:
 * ==================
 * This POJO (Plain Old Java Object) defines the CONTRACT between producers and consumers.
 * In event-driven architecture, services communicate via messages (events), not direct calls.
 * 
 * WHY EXPLICIT SCHEMA DEFINITION?
 * 1. DECOUPLING: API Service and Worker don't share code, only this schema
 * 2. VERSIONING: If we add new fields, old consumers still work (backward compatibility)
 * 3. VALIDATION: Catch schema violations early (before Cassandra write)
 * 
 * KAFKA EVENT FLOW:
 * 1. API Service creates MessageEvent object
 * 2. Serializes to JSON: {"message_id":"msg_123","conversation_id":"conv_abc",...}
 * 3. Publishes to Kafka topic "messages"
 * 4. Worker consumes from Kafka
 * 5. Deserializes JSON back to MessageEvent object
 * 6. Processes and persists to Cassandra
 * 
 * ALTERNATIVE APPROACHES (NOT USED HERE FOR EDUCATIONAL REASONS):
 * - Avro/Protobuf: Binary serialization (faster, smaller)
 * - Schema Registry: Centralized schema versioning (Confluent)
 * - JSON Schema: Formal schema validation
 * 
 * We use simple JSON for TRANSPARENCY - students can read Kafka messages with kafka-console-consumer.
 * 
 * @author Chat4All Educational Project
 * @version 1.0.0
 */
public class MessageEvent {
    
    /**
     * Unique identifier for the message
     * 
     * EDUCATIONAL NOTE: This is used for IDEMPOTENCY.
     * If Kafka delivers the same message twice (at-least-once delivery), the Worker
     * checks this ID in Cassandra before processing. If it exists, skip (deduplication).
     * 
     * FORMAT: "msg_" + UUID (e.g., "msg_550e8400-e29b-41d4-a716-446655440000")
     */
    private String messageId;
    
    /**
     * Identifier of the conversation this message belongs to
     * 
     * EDUCATIONAL NOTE: This is the PARTITION KEY for both Kafka and Cassandra.
     * 
     * KAFKA PARTITIONING:
     * - Kafka hashes conversation_id to determine partition (0, 1, or 2)
     * - All messages for conv_abc go to same partition (preserves order)
     * - Different conversations go to different partitions (parallel processing)
     * 
     * CASSANDRA PARTITIONING:
     * - conversation_id is the partition key in the messages table
     * - All messages for conv_abc are stored on the same nodes (locality)
     * - Enables efficient query: SELECT * FROM messages WHERE conversation_id = 'conv_abc'
     * 
     * FORMAT: "conv_" + UUID
     */
    private String conversationId;
    
    /**
     * User ID of the sender
     * 
     * FORMAT: "user_" + alphanumeric (e.g., "user_alice", "user_bob")
     * 
     * EDUCATIONAL NOTE: In a real system, this would be validated against a users table.
     * Here we keep it simple with hardcoded test users (user_a, user_b).
     */
    private String senderId;
    
    /**
     * User/Platform ID of the recipient (Phase 5: External Connectors)
     * 
     * OPTIONAL: Used for routing to external platforms
     * - NULL or empty: Local delivery (same as sender_id logic)
     * - "whatsapp:+5511999999999": Route to WhatsApp connector
     * - "instagram:@username": Route to Instagram connector
     * - "user_alice": Local delivery to user_alice
     * 
     * EDUCATIONAL NOTE: This enables multi-platform messaging.
     * The router-worker checks the prefix to determine where to send:
     * - "whatsapp:" → whatsapp-outbound Kafka topic → WhatsAppConnector
     * - "instagram:" → instagram-outbound Kafka topic → InstagramConnector
     * - Other → local delivery simulation
     * 
     * FORMAT: "<platform>:<platform_user_id>" or "user_<username>"
     */
    private String recipientId;
    
    /**
     * Message content (text)
     * 
     * VALIDATION:
     * - Non-empty (at least 1 character)
     * - Max 10,000 characters (see Constants.MAX_MESSAGE_SIZE_BYTES for binary limit)
     * 
     * EDUCATIONAL NOTE: Large messages (>1MB) should not be sent through Kafka.
     * For files/images, upload to Object Storage (MinIO/S3) and send only the URL.
     * This keeps Kafka topics lightweight and fast.
     */
    private String content;
    
    /**
     * Unix timestamp (milliseconds since epoch)
     * 
     * EXAMPLE: 1705497600000 = 2025-01-17T12:00:00Z
     * 
     * EDUCATIONAL NOTE: Always use UTC timestamps in distributed systems.
     * Why? Servers in different time zones. User in Brazil, server in Ireland.
     * 
     * CASSANDRA CLUSTERING:
     * - timestamp is the clustering key in the messages table
     * - Cassandra sorts messages by timestamp within each conversation
     * - Enables efficient query: SELECT * FROM messages WHERE conversation_id = 'conv_abc' ORDER BY timestamp DESC LIMIT 50
     */
    private long timestamp;
    
    /**
     * Event type (for extensibility)
     * 
     * CURRENT VALUES:
     * - "MESSAGE_SENT": Text message sent by user
     * 
     * FUTURE VALUES (Phase 2+):
     * - "FILE_UPLOADED": User uploaded a file
     * - "MESSAGE_EDITED": User edited existing message
     * - "MESSAGE_DELETED": User deleted message
     * 
     * EDUCATIONAL NOTE: Event-driven systems often use TYPE field for polymorphism.
     * Different event types can be sent to the same Kafka topic, then routed to different handlers.
     */
    private String eventType;
    
    /**
     * File attachment ID (Phase 2: Object Storage)
     * 
     * OPTIONAL: Only present for messages with file attachments
     * - NULL for text-only messages
     * - Non-null for file messages (references files table)
     * 
     * FORMAT: UUID (e.g., "550e8400-e29b-41d4-a716-446655440000")
     * 
     * EDUCATIONAL NOTE: We store only the file_id, not the entire file.
     * The file binary data is stored in MinIO (Object Storage).
     * To download the file:
     * 1. Query files table with file_id
     * 2. Get storage_path from metadata
     * 3. Generate presigned URL from MinIO
     * 4. Return URL to client for direct download
     */
    private String fileId;
    
    /**
     * File metadata (Phase 2: Object Storage)
     * 
     * OPTIONAL: Quick access to file info without joining files table
     * - NULL for text-only messages
     * - Contains: filename, size_bytes, mimetype, checksum
     * 
     * EDUCATIONAL NOTE: This is DENORMALIZATION for query performance.
     * Instead of always joining messages + files tables, we embed commonly-needed
     * metadata directly in the message record.
     * 
     * Trade-off: Slight data duplication vs. much faster queries
     * (NoSQL principle: design for read performance)
     * 
     * EXAMPLE:
     * {
     *   "filename": "photo.jpg",
     *   "size_bytes": "1048576",
     *   "mimetype": "image/jpeg",
     *   "checksum": "sha256:abc123..."
     * }
     */
    private java.util.Map<String, String> fileMetadata;
    
    /**
     * Default constructor (required for JSON deserialization)
     * 
     * EDUCATIONAL NOTE: Jackson, Gson, and manual JSON parsers need a no-arg constructor
     * to create an empty object, then populate fields via setters.
     */
    public MessageEvent() {
        // Empty constructor for deserialization
    }
    
    /**
     * Full constructor
     * 
     * @param messageId Unique message identifier
     * @param conversationId Conversation identifier
     * @param senderId Sender user ID
     * @param content Message text content
     * @param timestamp Unix timestamp (milliseconds)
     * @param eventType Event type (e.g., "MESSAGE_SENT")
     */
    public MessageEvent(String messageId, String conversationId, String senderId, 
                        String content, long timestamp, String eventType) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
        this.timestamp = timestamp;
        this.eventType = eventType;
    }
    
    // ==================
    // GETTERS & SETTERS
    // ==================
    
    public String getMessageId() {
        return messageId;
    }
    
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    
    public String getConversationId() {
        return conversationId;
    }
    
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public String getRecipientId() {
        return recipientId;
    }
    
    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public String getFileId() {
        return fileId;
    }
    
    public void setFileId(String fileId) {
        this.fileId = fileId;
    }
    
    public java.util.Map<String, String> getFileMetadata() {
        return fileMetadata;
    }
    
    public void setFileMetadata(java.util.Map<String, String> fileMetadata) {
        this.fileMetadata = fileMetadata;
    }
    
    // ====================
    // JSON SERIALIZATION
    // ====================
    
    /**
     * Serialize this object to JSON string
     * 
     * EDUCATIONAL NOTE: We implement manual JSON serialization for TRANSPARENCY.
     * In production, use libraries like Jackson or Gson.
     * 
     * EXAMPLE OUTPUT:
     * {
     *   "message_id": "msg_123",
     *   "conversation_id": "conv_abc",
     *   "sender_id": "user_alice",
     *   "content": "Hello, world!",
     *   "timestamp": 1705497600000,
     *   "event_type": "MESSAGE_SENT"
     * }
     * 
     * @return JSON string representation
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"message_id\":\"").append(escapeJson(messageId)).append("\",");
        json.append("\"conversation_id\":\"").append(escapeJson(conversationId)).append("\",");
        json.append("\"sender_id\":\"").append(escapeJson(senderId)).append("\",");
        
        // Optional: recipient_id (Phase 5: external connectors)
        if (recipientId != null && !recipientId.isEmpty()) {
            json.append("\"recipient_id\":\"").append(escapeJson(recipientId)).append("\",");
        }
        
        json.append("\"content\":\"").append(escapeJson(content)).append("\",");
        json.append("\"timestamp\":").append(timestamp).append(",");
        json.append("\"event_type\":\"").append(escapeJson(eventType)).append("\"");;
        
        // Optional fields (Phase 2: file attachments)
        if (fileId != null && !fileId.isEmpty()) {
            json.append(",\"file_id\":\"").append(escapeJson(fileId)).append("\"");
        }
        if (fileMetadata != null && !fileMetadata.isEmpty()) {
            json.append(",\"file_metadata\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, String> entry : fileMetadata.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(escapeJson(entry.getKey())).append("\":");
                json.append("\"").append(escapeJson(entry.getValue())).append("\"");
                first = false;
            }
            json.append("}");
        }
        
        json.append("}");
        return json.toString();
    }
    
    /**
     * Deserialize from JSON string
     * 
     * EDUCATIONAL NOTE: This is a SIMPLIFIED parser for educational purposes.
     * It assumes well-formed JSON (no error handling for malformed input).
     * In production, use Jackson's ObjectMapper.readValue().
     * 
     * PARSING STRATEGY:
     * 1. Find each field: "message_id":"value"
     * 2. Extract value between quotes
     * 3. Set field via setter
     * 
     * @param json JSON string
     * @return MessageEvent object
     * @throws IllegalArgumentException if JSON is malformed or missing required fields
     */
    public static MessageEvent fromJson(String json) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("JSON string cannot be null or empty");
        }
        
        MessageEvent event = new MessageEvent();
        
        // Extract fields using simple string parsing
        event.setMessageId(extractJsonValue(json, "message_id"));
        event.setConversationId(extractJsonValue(json, "conversation_id"));
        event.setSenderId(extractJsonValue(json, "sender_id"));
        
        // Optional: recipient_id (Phase 5)
        event.setRecipientId(extractJsonValue(json, "recipient_id"));
        
        event.setContent(extractJsonValue(json, "content"));
        event.setTimestamp(Long.parseLong(extractJsonValueRaw(json, "timestamp")));
        event.setEventType(extractJsonValue(json, "event_type"));
        
        // Optional fields (Phase 2: file attachments)
        String fileId = extractJsonValue(json, "file_id");
        if (fileId != null && !fileId.isEmpty()) {
            event.setFileId(fileId);
        }
        
        // Extract file_metadata map if present
        String fileMetadataJson = extractJsonObject(json, "file_metadata");
        if (fileMetadataJson != null && !fileMetadataJson.isEmpty()) {
            java.util.Map<String, String> metadata = new java.util.HashMap<>();
            // Simple parser for {"key":"value","key2":"value2"}
            String[] pairs = fileMetadataJson.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");
                    metadata.put(key, unescapeJson(value));
                }
            }
            event.setFileMetadata(metadata);
        }
        
        // Validate required fields
        if (event.getMessageId() == null || event.getMessageId().isEmpty()) {
            throw new IllegalArgumentException("message_id is required");
        }
        if (event.getConversationId() == null || event.getConversationId().isEmpty()) {
            throw new IllegalArgumentException("conversation_id is required");
        }
        if (event.getSenderId() == null || event.getSenderId().isEmpty()) {
            throw new IllegalArgumentException("sender_id is required");
        }
        if (event.getContent() == null || event.getContent().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        if (event.getEventType() == null || event.getEventType().isEmpty()) {
            throw new IllegalArgumentException("event_type is required");
        }
        
        return event;
    }
    
    /**
     * Extract string value from JSON
     * 
     * EXAMPLE: extractJsonValue('{"name":"Alice"}', "name") -> "Alice"
     * 
     * @param json JSON string
     * @param fieldName Field name to extract
     * @return Field value (unescaped)
     */
    private static String extractJsonValue(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\":\"";
        int startIndex = json.indexOf(pattern);
        if (startIndex == -1) {
            return null; // Field not found
        }
        startIndex += pattern.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) {
            return null; // Malformed JSON
        }
        return unescapeJson(json.substring(startIndex, endIndex));
    }
    
    /**
     * Extract numeric/boolean value from JSON (no quotes)
     * 
     * EXAMPLE: extractJsonValueRaw('{"count":42}', "count") -> "42"
     * 
     * @param json JSON string
     * @param fieldName Field name to extract
     * @return Field value as string (caller converts to int/long)
     */
    private static String extractJsonValueRaw(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\":";
        int startIndex = json.indexOf(pattern);
        if (startIndex == -1) {
            return null;
        }
        startIndex += pattern.length();
        int endIndex = json.indexOf(",", startIndex);
        if (endIndex == -1) {
            endIndex = json.indexOf("}", startIndex); // Last field
        }
        if (endIndex == -1) {
            return null;
        }
        return json.substring(startIndex, endIndex).trim();
    }
    
    /**
     * Extract JSON object value (Phase 2: for nested objects like file_metadata)
     * 
     * EXAMPLE: extractJsonObject('{"meta":{"key":"val"}}', "meta") -> '{"key":"val"}'
     * 
     * @param json JSON string
     * @param fieldName Field name to extract
     * @return JSON object content (without outer braces)
     */
    private static String extractJsonObject(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\":{";
        int startIndex = json.indexOf(pattern);
        if (startIndex == -1) {
            return null; // Field not found
        }
        startIndex += pattern.length() - 1; // Include opening brace
        
        // Find matching closing brace
        int braceCount = 0;
        int endIndex = startIndex;
        for (int i = startIndex; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') braceCount++;
            else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    endIndex = i;
                    break;
                }
            }
        }
        
        if (endIndex == startIndex) {
            return null; // No matching closing brace
        }
        
        // Return content between braces (excluding the braces themselves)
        return json.substring(startIndex + 1, endIndex);
    }
    
    /**
     * Escape special JSON characters
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
     * Unescape JSON characters
     * 
     * @param str Escaped string
     * @return Unescaped string
     */
    private static String unescapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\\"", "\"")
                  .replace("\\\\", "\\")
                  .replace("\\n", "\n")
                  .replace("\\r", "\r")
                  .replace("\\t", "\t");
    }
    
    /**
     * toString for debugging
     */
    @Override
    public String toString() {
        return "MessageEvent{" +
               "messageId='" + messageId + '\'' +
               ", conversationId='" + conversationId + '\'' +
               ", senderId='" + senderId + '\'' +
               ", content='" + (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
               ", timestamp=" + timestamp +
               ", eventType='" + eventType + '\'' +
               '}';
    }
}
