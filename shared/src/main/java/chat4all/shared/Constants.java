package chat4all.shared;

/**
 * ============================================================================
 * Constants - Application-wide configuration constants
 * ============================================================================
 * Purpose: Centralize configuration values used across services
 * Educational principle: Minimal Dependencies - avoid external config frameworks
 * ============================================================================
 */
public final class Constants {
    
    // Prevent instantiation
    private Constants() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated");
    }
    
    // ========================================================================
    // KAFKA CONFIGURATION
    // ========================================================================
    // Educational note: Kafka topic names are logical channels for events
    // Messages with same partition key (conversation_id) go to same partition
    // ========================================================================
    
    /**
     * Main Kafka topic for message events.
     * Partitioning strategy: Use conversation_id as key to preserve message order
     * per conversation while enabling parallel processing across conversations.
     */
    public static final String KAFKA_TOPIC_MESSAGES = getEnv("KAFKA_TOPIC_MESSAGES", "messages");
    
    /**
     * Kafka bootstrap servers (broker addresses).
     * Educational note: In production, use multiple brokers for fault tolerance.
     * Phase 1: Single broker sufficient for learning.
     */
    public static final String KAFKA_BOOTSTRAP_SERVERS = 
        getEnv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    
    /**
     * Kafka consumer group ID for router workers.
     * Educational note: All workers in same group share partition processing.
     * Kafka automatically distributes partitions across group members.
     */
    public static final String KAFKA_CONSUMER_GROUP = 
        getEnv("KAFKA_GROUP_ID", "router-worker-group");
    
    // ========================================================================
    // CASSANDRA CONFIGURATION
    // ========================================================================
    // Educational note: Cassandra uses distributed architecture (no master node)
    // Contact points = entry nodes for cluster discovery
    // ========================================================================
    
    /**
     * Cassandra contact points (initial connection nodes).
     * Educational note: Client connects to any node, cluster topology discovered automatically.
     */
    public static final String CASSANDRA_CONTACT_POINTS = 
        getEnv("CASSANDRA_CONTACT_POINTS", "127.0.0.1");
    
    /**
     * Cassandra native transport port (CQL protocol).
     */
    public static final int CASSANDRA_PORT = 
        Integer.parseInt(getEnv("CASSANDRA_PORT", "9042"));
    
    /**
     * Cassandra keyspace (analogous to database in RDBMS).
     * Educational note: Keyspace defines replication strategy and factor.
     */
    public static final String CASSANDRA_KEYSPACE = 
        getEnv("CASSANDRA_KEYSPACE", "chat4all");
    
    /**
     * Cassandra datacenter name (for replication strategy).
     */
    public static final String CASSANDRA_DATACENTER = 
        getEnv("CASSANDRA_DC", "dc1");
    
    // ========================================================================
    // JWT AUTHENTICATION CONFIGURATION
    // ========================================================================
    // Educational note: JWT = JSON Web Token (RFC 7519)
    // Stateless authentication: no server-side session storage needed
    // ========================================================================
    
    /**
     * JWT secret key for HMAC-SHA256 signing.
     * IMPORTANT: In production, use strong random key and secure storage (vault).
     * Phase 1: Static key acceptable for educational purposes.
     */
    public static final String JWT_SECRET = 
        getEnv("JWT_SECRET", "chat4all-secret-key-change-in-production");
    
    /**
     * JWT token expiration time in hours.
     * Educational note: Balance security (short-lived) vs UX (not too short).
     */
    public static final int JWT_EXPIRATION_HOURS = 
        Integer.parseInt(getEnv("JWT_EXPIRATION_HOURS", "1"));
    
    // ========================================================================
    // API SERVICE CONFIGURATION
    // ========================================================================
    
    /**
     * API service HTTP port.
     * Educational note: Standard HTTP = 80, HTTPS = 443. Use 8080 for dev.
     */
    public static final int API_PORT = 
        Integer.parseInt(getEnv("API_PORT", "8080"));
    
    /**
     * API base path for versioning.
     * Educational note: API versioning enables backward compatibility.
     */
    public static final String API_BASE_PATH = "/v1";
    
    // ========================================================================
    // MESSAGE VALIDATION
    // ========================================================================
    
    /**
     * Maximum message content size in bytes (10 KB).
     * Educational note: Prevents abuse, ensures reasonable Kafka message size.
     * Phase 1: Text only. Phase 2: Large files handled separately (object storage).
     */
    public static final int MAX_MESSAGE_CONTENT_SIZE = 10 * 1024; // 10 KB
    
    /**
     * Maximum messages to return per page (pagination).
     * Educational note: Limits result set size, improves performance.
     */
    public static final int DEFAULT_PAGE_LIMIT = 50;
    
    // ========================================================================
    // RETRY CONFIGURATION
    // ========================================================================
    // Educational note: Distributed systems need retry logic for transient failures
    // Exponential backoff prevents overwhelming failing services
    // ========================================================================
    
    /**
     * Maximum retry attempts for Cassandra operations.
     */
    public static final int RETRY_MAX_ATTEMPTS = 
        Integer.parseInt(getEnv("RETRY_MAX_ATTEMPTS", "3"));
    
    /**
     * Initial backoff delay in milliseconds for retry logic.
     * Educational note: Exponential backoff: delay = initial * (2 ^ attempt)
     */
    public static final int RETRY_BACKOFF_MS = 
        Integer.parseInt(getEnv("RETRY_BACKOFF_MS", "1000"));
    
    // ========================================================================
    // MESSAGE STATUS
    // ========================================================================
    // Educational note: Message lifecycle tracking (finite state machine)
    // ========================================================================
    
    /**
     * Message status: Accepted by API, published to Kafka.
     */
    public static final String STATUS_SENT = "SENT";
    
    /**
     * Message status: Delivered to recipient's device/client.
     */
    public static final String STATUS_DELIVERED = "DELIVERED";
    
    /**
     * Message status: Read by recipient (Phase 2 - not implemented in Phase 1).
     */
    public static final String STATUS_READ = "READ";
    
    /**
     * Message status: Pending delivery (recipient offline).
     */
    public static final String STATUS_PENDING = "PENDING";
    
    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    /**
     * Get environment variable with fallback to default value.
     * Educational note: 12-factor app principle - configuration via environment.
     * 
     * @param key Environment variable name
     * @param defaultValue Fallback if env var not set
     * @return Environment variable value or default
     */
    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
