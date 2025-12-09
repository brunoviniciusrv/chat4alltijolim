package chat4all.shared;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Structured Logger for Chat4All Distributed System
 * 
 * EDUCATIONAL PURPOSE:
 * ==================
 * This logger implements STRUCTURED LOGGING, a critical practice in distributed systems.
 * Unlike traditional text logs ("User sent message"), structured logs output machine-parseable JSON:
 * {"timestamp":"2025-01-17T10:30:00Z","level":"INFO","message":"Message sent","trace_id":"abc123"}
 * 
 * WHY STRUCTURED LOGGING IN DISTRIBUTED SYSTEMS?
 * 1. CORRELATION: trace_id links logs across multiple services (API -> Kafka -> Worker -> Cassandra)
 * 2. AGGREGATION: Tools like ELK Stack can search/filter JSON fields efficiently
 * 3. DEBUGGING: Find all logs for a specific conversation_id or message_id instantly
 * 
 * DISTRIBUTED TRACING CONCEPTS:
 * - trace_id: Unique ID that follows a request through all services (propagated via Kafka headers)
 * - conversation_id: Business context (which chat is this about?)
 * - message_id: Granular context (which specific message?)
 * 
 * NOTE: In production, use libraries like SLF4J + Logback with JSON encoders.
 * Here we implement manually for EDUCATIONAL TRANSPARENCY - you see exactly how it works.
 * 
 * @author Chat4All Educational Project
 * @version 1.0.0
 */
public class Logger {
    
    /**
     * Log level enum
     * Ordered by severity: TRACE < DEBUG < INFO < WARN < ERROR
     */
    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }
    
    /**
     * Thread-local context for distributed tracing
     * 
     * EDUCATIONAL NOTE: ThreadLocal stores data per-thread without global synchronization.
     * This is critical for performance in multi-threaded servers.
     * Each HTTP request thread has its own trace_id, preventing cross-contamination.
     */
    private static final ThreadLocal<Map<String, String>> CONTEXT = ThreadLocal.withInitial(HashMap::new);
    
    /**
     * Component name (e.g., "api-service", "router-worker")
     * Set once at application startup
     */
    private final String componentName;
    
    /**
     * Minimum log level to output (configurable via environment)
     * Default: INFO (suppress DEBUG/TRACE in production)
     */
    private final Level minLevel;
    
    /**
     * ISO 8601 timestamp formatter
     * Example: 2025-01-17T14:30:45.123Z
     */
    private static final DateTimeFormatter ISO_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    
    /**
     * Constructor
     * 
     * @param componentName Service identifier (e.g., "api-service")
     */
    public Logger(String componentName) {
        this.componentName = componentName;
        this.minLevel = parseLogLevel(System.getenv("LOG_LEVEL"));
    }
    
    /**
     * Parse log level from environment variable
     * 
     * @param levelStr Environment variable value (e.g., "INFO", "DEBUG")
     * @return Corresponding Level enum, defaults to INFO
     */
    private Level parseLogLevel(String levelStr) {
        if (levelStr == null || levelStr.isEmpty()) {
            return Level.INFO; // Default level
        }
        try {
            return Level.valueOf(levelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid LOG_LEVEL: " + levelStr + ", defaulting to INFO");
            return Level.INFO;
        }
    }
    
    // ========================
    // DISTRIBUTED TRACING API
    // ========================
    
    /**
     * Set the trace_id for the current thread/request
     * 
     * WHEN TO CALL:
     * - API Service: At the start of each HTTP request (generate new UUID)
     * - Worker Service: When consuming Kafka message (extract from Kafka headers)
     * 
     * EDUCATIONAL NOTE: This propagates trace_id through the entire request lifecycle.
     * Without this, you can't correlate logs from different services for the same user action.
     * 
     * @param traceId Unique identifier for this distributed transaction
     */
    public static void setTraceId(String traceId) {
        CONTEXT.get().put("trace_id", traceId);
    }
    
    /**
     * Set the conversation_id for the current context
     * 
     * WHEN TO CALL:
     * - When processing a message belonging to a specific conversation
     * - Helps filter logs: "Show me all logs for conversation conv_abc123"
     * 
     * @param conversationId Business context identifier
     */
    public static void setConversationId(String conversationId) {
        CONTEXT.get().put("conversation_id", conversationId);
    }
    
    /**
     * Set the message_id for the current context
     * 
     * WHEN TO CALL:
     * - When processing a specific message
     * - Most granular level: "Show me all logs for message msg_xyz789"
     * 
     * @param messageId Message identifier
     */
    public static void setMessageId(String messageId) {
        CONTEXT.get().put("message_id", messageId);
    }
    
    /**
     * Clear the thread-local context
     * 
     * WHEN TO CALL:
     * - At the END of each HTTP request (in finally block)
     * - After Kafka message processing completes
     * 
     * CRITICAL: Without this, ThreadLocal memory leaks occur in thread pools!
     * Old trace_id values from previous requests contaminate new requests.
     */
    public static void clearContext() {
        CONTEXT.get().clear();
    }
    
    // ================
    // LOGGING METHODS
    // ================
    
    /**
     * Log a TRACE message (most verbose)
     * Use for: Very detailed debugging (e.g., "Entering method X with params Y")
     */
    public void trace(String message) {
        log(Level.TRACE, message, null);
    }
    
    /**
     * Log a DEBUG message
     * Use for: Development debugging (e.g., "Kafka partition assigned: 2")
     */
    public void debug(String message) {
        log(Level.DEBUG, message, null);
    }
    
    /**
     * Log an INFO message
     * Use for: Normal operations (e.g., "Message sent successfully")
     */
    public void info(String message) {
        log(Level.INFO, message, null);
    }
    
    /**
     * Log a WARN message
     * Use for: Recoverable errors (e.g., "Cassandra retry attempt 2/3")
     */
    public void warn(String message) {
        log(Level.WARN, message, null);
    }
    
    /**
     * Log a WARN message with exception
     */
    public void warn(String message, Throwable throwable) {
        log(Level.WARN, message, throwable);
    }
    
    /**
     * Log an ERROR message
     * Use for: Failures requiring attention (e.g., "Failed to save to Cassandra after 3 retries")
     */
    public void error(String message) {
        log(Level.ERROR, message, null);
    }
    
    /**
     * Log an ERROR message with exception
     */
    public void error(String message, Throwable throwable) {
        log(Level.ERROR, message, throwable);
    }
    
    /**
     * Core logging implementation
     * 
     * STRUCTURED LOG FORMAT (JSON):
     * {
     *   "timestamp": "2025-01-17T14:30:45.123Z",
     *   "level": "INFO",
     *   "component": "api-service",
     *   "message": "Message published to Kafka",
     *   "trace_id": "550e8400-e29b-41d4-a716-446655440000",
     *   "conversation_id": "conv_abc123",
     *   "message_id": "msg_xyz789",
     *   "error": "java.net.ConnectException: Connection refused",
     *   "stack_trace": "at java.net.Socket.connect..."
     * }
     * 
     * @param level Log level
     * @param message Log message
     * @param throwable Optional exception
     */
    private void log(Level level, String message, Throwable throwable) {
        // Skip if below minimum level (performance optimization)
        if (level.ordinal() < minLevel.ordinal()) {
            return;
        }
        
        // Build JSON log entry
        StringBuilder json = new StringBuilder();
        json.append("{");
        
        // Timestamp (ISO 8601 UTC)
        json.append("\"timestamp\":\"").append(ISO_FORMATTER.format(Instant.now())).append("\",");
        
        // Level
        json.append("\"level\":\"").append(level.name()).append("\",");
        
        // Component
        json.append("\"component\":\"").append(escapeJson(componentName)).append("\",");
        
        // Message
        json.append("\"message\":\"").append(escapeJson(message)).append("\"");
        
        // Distributed tracing context
        Map<String, String> context = CONTEXT.get();
        if (context.containsKey("trace_id")) {
            json.append(",\"trace_id\":\"").append(escapeJson(context.get("trace_id"))).append("\"");
        }
        if (context.containsKey("conversation_id")) {
            json.append(",\"conversation_id\":\"").append(escapeJson(context.get("conversation_id"))).append("\"");
        }
        if (context.containsKey("message_id")) {
            json.append(",\"message_id\":\"").append(escapeJson(context.get("message_id"))).append("\"");
        }
        
        // Exception details
        if (throwable != null) {
            json.append(",\"error\":\"").append(escapeJson(throwable.toString())).append("\"");
            json.append(",\"stack_trace\":\"").append(escapeJson(getStackTraceString(throwable))).append("\"");
        }
        
        json.append("}");
        
        // Output to stdout (Docker captures this)
        // In production, this would go to a log aggregator (e.g., Fluentd -> Elasticsearch)
        System.out.println(json.toString());
    }
    
    /**
     * Escape special JSON characters
     * 
     * EDUCATIONAL NOTE: Without escaping, a log message containing " breaks JSON parsing.
     * Example: Message "User said: "Hello"" -> "User said: \"Hello\""
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
     * Convert exception stack trace to string
     * 
     * @param throwable Exception
     * @return Stack trace as string (first 5 lines to avoid log spam)
     */
    private String getStackTraceString(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] elements = throwable.getStackTrace();
        int limit = Math.min(5, elements.length); // Limit to 5 lines
        for (int i = 0; i < limit; i++) {
            sb.append(elements[i].toString());
            if (i < limit - 1) sb.append(" | ");
        }
        if (elements.length > 5) {
            sb.append(" ... (").append(elements.length - 5).append(" more)");
        }
        return sb.toString();
    }
}
