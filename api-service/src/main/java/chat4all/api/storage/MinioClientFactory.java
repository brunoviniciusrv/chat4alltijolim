package chat4all.api.storage;

import io.minio.MinioClient;
import java.util.Objects;

/**
 * MinioClientFactory - Singleton factory for MinIO client
 * 
 * Purpose: Centralized MinIO client management with lazy initialization
 * 
 * Educational notes:
 * - Singleton pattern: Only one MinIO client instance per application
 * - Thread-safe: Double-checked locking for concurrent access
 * - Configuration from environment variables (12-factor app principle)
 * - Connection pooling handled internally by MinIO SDK
 * 
 * Why singleton?
 * - MinIO client is thread-safe and expensive to create
 * - Reuse HTTP connection pool across requests
 * - Centralized configuration management
 * 
 * Environment Variables Required:
 * - MINIO_ENDPOINT: MinIO server URL (e.g., http://minio:9000)
 * - MINIO_ACCESS_KEY: Access credentials (default: admin)
 * - MINIO_SECRET_KEY: Secret credentials (default: password123)
 * 
 * @since Phase 2 (File Upload API)
 */
public class MinioClientFactory {
    
    // Singleton instance (volatile for thread-safety)
    private static volatile MinioClient instance;
    
    // Configuration from environment
    private static final String MINIO_ENDPOINT = getEnvOrDefault("MINIO_ENDPOINT", "http://minio:9000");
    private static final String MINIO_ACCESS_KEY = getEnvOrDefault("MINIO_ACCESS_KEY", "admin");
    private static final String MINIO_SECRET_KEY = getEnvOrDefault("MINIO_SECRET_KEY", "password123");
    
    // Private constructor to prevent instantiation
    private MinioClientFactory() {
        throw new UnsupportedOperationException("Utility class - cannot instantiate");
    }
    
    /**
     * Get or create MinIO client instance (thread-safe singleton)
     * 
     * Educational note: Double-checked locking pattern
     * - First check (outside synchronized): Fast path for initialized instance
     * - Second check (inside synchronized): Prevents race condition
     * - volatile keyword: Ensures visibility across threads
     * 
     * @return MinioClient instance configured from environment
     * @throws IllegalStateException if configuration is invalid
     */
    public static MinioClient getInstance() {
        // Fast path: return if already initialized (no synchronization overhead)
        if (instance == null) {
            // Slow path: synchronize only on first initialization
            synchronized (MinioClientFactory.class) {
                // Double-check: another thread might have initialized while we waited
                if (instance == null) {
                    instance = createClient();
                }
            }
        }
        return instance;
    }
    
    /**
     * Create and configure MinIO client
     * 
     * Educational note: Builder pattern for complex object construction
     * - Fluent API: chain method calls
     * - Clear configuration steps
     * - Validates required parameters
     * 
     * @return Configured MinioClient instance
     * @throws IllegalStateException if configuration is missing or invalid
     */
    private static MinioClient createClient() {
        try {
            // Validate configuration
            Objects.requireNonNull(MINIO_ENDPOINT, "MINIO_ENDPOINT cannot be null");
            Objects.requireNonNull(MINIO_ACCESS_KEY, "MINIO_ACCESS_KEY cannot be null");
            Objects.requireNonNull(MINIO_SECRET_KEY, "MINIO_SECRET_KEY cannot be null");
            
            // Build client using MinIO SDK builder pattern
            MinioClient client = MinioClient.builder()
                    .endpoint(MINIO_ENDPOINT)
                    .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                    .build();
            
            System.out.println("[MinioClientFactory] Client initialized successfully");
            System.out.println("[MinioClientFactory] Endpoint: " + MINIO_ENDPOINT);
            
            return client;
            
        } catch (Exception e) {
            String errorMsg = "Failed to create MinIO client: " + e.getMessage();
            System.err.println("[MinioClientFactory] ERROR: " + errorMsg);
            throw new IllegalStateException(errorMsg, e);
        }
    }
    
    /**
     * Get environment variable with default fallback
     * 
     * Educational note: Configuration best practices
     * - Environment variables for deployment flexibility
     * - Defaults for local development
     * - No hardcoded credentials in production
     * 
     * @param key Environment variable name
     * @param defaultValue Fallback if not set
     * @return Environment value or default
     */
    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
    
    /**
     * Get configured bucket name from environment
     * 
     * @return Bucket name (default: chat4all-files)
     */
    public static String getBucketName() {
        return getEnvOrDefault("MINIO_BUCKET", "chat4all-files");
    }
    
    /**
     * Create a client configured for external/presigned URL generation
     * 
     * Educational note: Why separate client for presigned URLs?
     * - Presigned URLs embed the endpoint hostname in the signature
     * - Internal endpoints (minio:9000) don't work for external clients
     * - External endpoints (localhost:9000) work from outside Docker
     * - S3 signature algorithm includes Host header in HMAC calculation
     * 
     * Problem: Can't just replace hostname in generated URL
     * - Signature was calculated with internal hostname
     * - Changing hostname invalidates signature → 403 Forbidden
     * 
     * Solution: Generate URLs with external endpoint from the start
     * - Configure client with MINIO_EXTERNAL_ENDPOINT
     * - Signature will be valid for external access
     * - URLs work from browser, curl, etc.
     * 
     * @return MinioClient configured for external access
     */
    public static MinioClient getExternalClient() {
        String externalEndpoint = getEnvOrDefault("MINIO_EXTERNAL_ENDPOINT", MINIO_ENDPOINT);
        
        try {
            MinioClient externalClient = MinioClient.builder()
                    .endpoint(externalEndpoint)
                    .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                    .build();
            
            System.out.println("[MinioClientFactory] External client created for presigned URLs");
            System.out.println("[MinioClientFactory] External endpoint: " + externalEndpoint);
            
            return externalClient;
            
        } catch (Exception e) {
            String errorMsg = "Failed to create external MinIO client: " + e.getMessage();
            System.err.println("[MinioClientFactory] ERROR: " + errorMsg);
            throw new IllegalStateException(errorMsg, e);
        }
    }
    
    /**
     * Reset singleton instance (for testing only)
     * 
     * Warning: Not thread-safe, only use in tests
     */
    static void resetForTesting() {
        instance = null;
    }
}
