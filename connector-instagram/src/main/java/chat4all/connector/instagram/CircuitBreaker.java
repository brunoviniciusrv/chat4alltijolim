package chat4all.connector.instagram;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Circuit Breaker implementation for Instagram Connector.
 * 
 * Pattern: Prevents cascading failures by stopping requests to failing services.
 * 
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Failure threshold reached, requests fail fast (no API calls)
 * - HALF_OPEN: Testing if service recovered, allow 1 test request
 * 
 * Transition Logic:
 * - CLOSED → OPEN: After 5 consecutive failures
 * - OPEN → HALF_OPEN: After 30 seconds timeout
 * - HALF_OPEN → CLOSED: If test request succeeds
 * - HALF_OPEN → OPEN: If test request fails
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class CircuitBreaker {
    
    public enum State {
        CLOSED,      // Normal operation
        OPEN,        // Failing fast
        HALF_OPEN    // Testing recovery
    }
    
    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_TIMEOUT_MS = 30_000; // 30 seconds
    
    private volatile State state;
    private final AtomicInteger consecutiveFailures;
    private final AtomicLong lastFailureTime;
    private final ConnectorMetricsRegistry metricsRegistry;
    
    public CircuitBreaker(ConnectorMetricsRegistry metricsRegistry) {
        this.state = State.CLOSED;
        this.consecutiveFailures = new AtomicInteger(0);
        this.lastFailureTime = new AtomicLong(0);
        this.metricsRegistry = metricsRegistry;
        
        // Initialize metrics
        metricsRegistry.setCircuitBreakerState("CLOSED");
        
        System.out.println("✅ Circuit Breaker initialized (state=CLOSED)");
    }
    
    /**
     * Check if request should be allowed through
     * 
     * @return true if request allowed, false if circuit is OPEN
     */
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        
        if (state == State.OPEN) {
            // Check if timeout expired
            long now = System.currentTimeMillis();
            long timeSinceFailure = now - lastFailureTime.get();
            
            if (timeSinceFailure >= OPEN_TIMEOUT_MS) {
                // Transition to HALF_OPEN
                transitionTo(State.HALF_OPEN);
                return true; // Allow test request
            }
            
            return false; // Still OPEN, reject request
        }
        
        // HALF_OPEN state: allow single test request
        return true;
    }
    
    /**
     * Record successful API call
     * Resets failure counter and may close circuit
     */
    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        
        if (state == State.HALF_OPEN) {
            // Test request succeeded, close circuit
            transitionTo(State.CLOSED);
            System.out.println("✅ Circuit Breaker: Test request succeeded, circuit CLOSED");
        }
    }
    
    /**
     * Record failed API call
     * Increments failure counter and may open circuit
     */
    public synchronized void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        System.err.println("⚠️ Circuit Breaker: Failure recorded (consecutive=" + failures + ")");
        
        if (state == State.HALF_OPEN) {
            // Test request failed, reopen circuit
            transitionTo(State.OPEN);
            System.err.println("❌ Circuit Breaker: Test request failed, circuit OPEN again");
            return;
        }
        
        if (state == State.CLOSED && failures >= FAILURE_THRESHOLD) {
            // Threshold reached, open circuit
            transitionTo(State.OPEN);
            System.err.println("❌ Circuit Breaker: Failure threshold reached (" + FAILURE_THRESHOLD + "), circuit OPEN");
        }
    }
    
    /**
     * Get current circuit breaker state
     */
    public State getState() {
        return state;
    }
    
    /**
     * Get consecutive failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
    
    /**
     * Transition to new state and update metrics
     */
    private void transitionTo(State newState) {
        State oldState = this.state;
        this.state = newState;
        
        // Reset failure counter when closing
        if (newState == State.CLOSED) {
            consecutiveFailures.set(0);
        }
        
        // Update metrics
        metricsRegistry.setCircuitBreakerState(newState.name());
        metricsRegistry.recordCircuitBreakerTransition(oldState.name(), newState.name());
        
        System.out.println("🔄 Circuit Breaker: " + oldState + " → " + newState);
    }
}
