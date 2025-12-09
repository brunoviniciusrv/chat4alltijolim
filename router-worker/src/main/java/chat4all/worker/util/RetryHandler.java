package chat4all.worker.util;

/**
 * RetryHandler - Exponential Backoff for Distributed System Retries
 * 
 * EDUCATIONAL PURPOSE:
 * ==================
 * This class teaches students about RESILIENCE PATTERNS in distributed systems.
 * 
 * THE PROBLEM: Transient Failures
 * ============================
 * Distributed systems experience temporary failures:
 * - Network timeouts (packet loss, congestion)
 * - Database overload (Cassandra node temporarily slow)
 * - Kafka broker restart (leader election in progress)
 * 
 * NAIVE SOLUTION (DON'T DO THIS):
 * while (!success) {
 *     try { writeToDatabase(); success = true; }
 *     catch (Exception e) { Thread.sleep(100); } // WRONG!
 * }
 * 
 * PROBLEMS WITH NAIVE RETRY:
 * 1. FIXED DELAY: Retries every 100ms, wastes time if failure is persistent
 * 2. NO LIMIT: Infinite loop can hang forever
 * 3. THUNDERING HERD: All clients retry simultaneously, overload system
 * 
 * EXPONENTIAL BACKOFF SOLUTION (THIS CLASS):
 * ========================================
 * Retry delay DOUBLES after each failure:
 * - Attempt 1: Wait 0ms (immediate)
 * - Attempt 2: Wait 1000ms (1 second)
 * - Attempt 3: Wait 2000ms (2 seconds)
 * - Attempt 4: Wait 4000ms (4 seconds)
 * - Attempt 5: Wait 8000ms (8 seconds)
 * - MAX: Wait 30000ms (30 seconds)
 * 
 * BENEFITS:
 * 1. FAST RECOVERY: Immediate retry if transient glitch
 * 2. ADAPTIVE BACKOFF: Longer waits if problem persists
 * 3. BOUNDED RETRIES: Stops after MAX_RETRIES (prevents infinite loops)
 * 4. JITTER: Random delay variation prevents thundering herd
 * 
 * REAL-WORLD EXAMPLES:
 * - AWS SDK: Exponential backoff for API rate limits
 * - Kafka Consumer: Exponential backoff for broker reconnection
 * - HTTP clients: 429 Too Many Requests -> exponential backoff
 * 
 * @author Chat4All Educational Project
 * @version 1.0.0
 */
public class RetryHandler {
    
    /**
     * Maximum number of retry attempts (including initial attempt)
     * 
     * EXAMPLE: MAX_RETRIES = 5 means:
     * - 1 initial attempt
     * - 4 retry attempts
     * - Total: 5 attempts
     * 
     * EDUCATIONAL NOTE: This value is a tradeoff:
     * - Too low (e.g., 2): Give up too quickly on transient failures
     * - Too high (e.g., 20): Waste time on permanent failures (e.g., wrong password)
     * 
     * COMMON VALUES:
     * - API calls to external services: 3-5 retries
     * - Internal database writes: 5-10 retries
     * - Critical operations (payment processing): 10-20 retries
     */
    private static final int MAX_RETRIES = 5;
    
    /**
     * Initial retry delay in milliseconds
     * 
     * EDUCATIONAL NOTE: First retry happens quickly (1 second) because most
     * transient failures resolve within 1-2 seconds (network glitch, GC pause).
     */
    private static final long INITIAL_DELAY_MS = 1000; // 1 second
    
    /**
     * Maximum retry delay in milliseconds
     * 
     * EDUCATIONAL NOTE: Cap exponential growth to prevent absurdly long waits.
     * Without this cap: Attempt 10 = 2^10 * 1000ms = 1024 seconds = 17 minutes!
     * With cap: Attempt 5+ = 30 seconds (reasonable for distributed system)
     */
    private static final long MAX_DELAY_MS = 30000; // 30 seconds
    
    /**
     * Execute an operation with exponential backoff retry
     * 
     * USAGE EXAMPLE (Cassandra write):
     * RetryHandler.executeWithRetry(() -> {
     *     cassandraSession.execute("INSERT INTO messages ...");
     *     return null;
     * }, "Cassandra write");
     * 
     * USAGE EXAMPLE (Kafka produce):
     * RetryHandler.executeWithRetry(() -> {
     *     producer.send(record).get(); // Block until acknowledged
     *     return null;
     * }, "Kafka produce");
     * 
     * @param operation Operation to execute (may throw exceptions)
     * @param operationName Human-readable name for logging
     * @param <T> Return type of operation
     * @return Result of successful operation
     * @throws RetryExhaustedException if all retries fail
     */
    public static <T> T executeWithRetry(RetryableOperation<T> operation, String operationName) 
            throws RetryExhaustedException {
        
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // Try to execute operation
                return operation.execute();
                
            } catch (Exception e) {
                lastException = e;
                
                // Log failure
                System.err.println(String.format(
                    "Retry attempt %d/%d failed for [%s]: %s",
                    attempt, MAX_RETRIES, operationName, e.getMessage()
                ));
                
                // If last attempt, give up
                if (attempt >= MAX_RETRIES) {
                    break;
                }
                
                // Calculate exponential backoff delay
                long delayMs = calculateDelay(attempt);
                
                // Log retry delay
                System.out.println(String.format(
                    "Retrying [%s] in %dms (attempt %d/%d)",
                    operationName, delayMs, attempt + 1, MAX_RETRIES
                ));
                
                // Wait before retry
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    // Restore interrupt status
                    Thread.currentThread().interrupt();
                    throw new RetryExhaustedException(
                        "Retry interrupted for [" + operationName + "]",
                        lastException
                    );
                }
            }
        }
        
        // All retries exhausted
        throw new RetryExhaustedException(
            "Failed after " + MAX_RETRIES + " attempts for [" + operationName + "]",
            lastException
        );
    }
    
    /**
     * Calculate exponential backoff delay with jitter
     * 
     * FORMULA: delay = min(INITIAL_DELAY * 2^(attempt-1), MAX_DELAY) + jitter
     * 
     * EXAMPLE DELAYS (without jitter):
     * - Attempt 1: 1000ms * 2^0 = 1000ms (1 second)
     * - Attempt 2: 1000ms * 2^1 = 2000ms (2 seconds)
     * - Attempt 3: 1000ms * 2^2 = 4000ms (4 seconds)
     * - Attempt 4: 1000ms * 2^3 = 8000ms (8 seconds)
     * - Attempt 5: 1000ms * 2^4 = 16000ms (16 seconds)
     * - Attempt 6: 1000ms * 2^5 = 32000ms -> capped to 30000ms (30 seconds)
     * 
     * JITTER: Add random variation (±10%) to prevent thundering herd
     * 
     * THUNDERING HERD PROBLEM:
     * Without jitter, all clients retry at EXACTLY the same time:
     * - 1000 clients fail at T=0
     * - All retry at T=1000ms (1 second later)
     * - Server gets 1000 simultaneous requests -> overload -> all fail again!
     * - All retry at T=3000ms (2 seconds later)
     * - Cycle continues...
     * 
     * With jitter, retries are spread across time:
     * - Client 1 retries at T=950ms
     * - Client 2 retries at T=1020ms
     * - Client 3 retries at T=890ms
     * - ...
     * - Server load is distributed, recovery is faster
     * 
     * @param attempt Retry attempt number (1-based)
     * @return Delay in milliseconds
     */
    private static long calculateDelay(int attempt) {
        // Exponential backoff: 2^(attempt-1) * INITIAL_DELAY_MS
        long exponentialDelay = (long) (INITIAL_DELAY_MS * Math.pow(2, attempt - 1));
        
        // Cap at maximum delay
        long cappedDelay = Math.min(exponentialDelay, MAX_DELAY_MS);
        
        // Add jitter: random variation of ±10%
        double jitterFactor = 0.9 + (Math.random() * 0.2); // Random value in [0.9, 1.1]
        long delayWithJitter = (long) (cappedDelay * jitterFactor);
        
        return delayWithJitter;
    }
    
    /**
     * Functional interface for retryable operations
     * 
     * EDUCATIONAL NOTE: This is a lambda-friendly interface.
     * Instead of creating anonymous classes:
     * RetryHandler.executeWithRetry(new RetryableOperation<Void>() {
     *     public Void execute() throws Exception { ... }
     * }, "operation");
     * 
     * Use lambda syntax:
     * RetryHandler.executeWithRetry(() -> { ... }, "operation");
     * 
     * @param <T> Return type
     */
    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
    
    /**
     * Exception thrown when all retry attempts fail
     * 
     * EDUCATIONAL NOTE: This is a checked exception (extends Exception, not RuntimeException).
     * Callers MUST handle this explicitly, forcing them to decide:
     * - Log and skip message? (at-least-once delivery fails)
     * - Crash worker? (fail-fast, ops team investigates)
     * - Write to dead-letter queue? (for later reprocessing)
     */
    public static class RetryExhaustedException extends Exception {
        private final Exception lastException;
        
        public RetryExhaustedException(String message, Exception lastException) {
            super(message, lastException);
            this.lastException = lastException;
        }
        
        public Exception getLastException() {
            return lastException;
        }
    }
}
