package chat4all.connector.instagram;

import io.micrometer.core.instrument.*;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized metrics registry for Instagram Connector.
 * 
 * Metrics collected:
 * - messages_sent_total{channel="instagram", status="success|failed"} - Counter
 * - connector_api_duration_seconds{channel="instagram"} - Timer (histogram)
 * - circuit_breaker_state{channel="instagram"} - Gauge (0=closed, 1=open, 0.5=half_open)
 * - circuit_breaker_transitions_total{channel="instagram", from, to} - Counter
 * 
 * Exposed via HTTP endpoint /actuator/prometheus on port 8083
 */
public class ConnectorMetricsRegistry {
    
    private static ConnectorMetricsRegistry instance;
    
    private final PrometheusMeterRegistry prometheusRegistry;
    private final Counter messagesSentSuccess;
    private final Counter messagesSentFailed;
    private final Timer apiDuration;
    private final AtomicInteger circuitBreakerState; // 0=closed, 1=open, 2=half_open
    private final ConcurrentHashMap<String, Counter> transitionCounters;
    
    private ConnectorMetricsRegistry() {
        this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        
        // Register JVM metrics (memory, threads, GC)
        new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(prometheusRegistry);
        new io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics().bindTo(prometheusRegistry);
        new io.micrometer.core.instrument.binder.jvm.JvmGcMetrics().bindTo(prometheusRegistry);
        
        // Messages sent counter
        this.messagesSentSuccess = Counter.builder("messages_sent_total")
                .description("Total messages sent through Instagram connector")
                .tag("channel", "instagram")
                .tag("status", "success")
                .register(prometheusRegistry);
        
        this.messagesSentFailed = Counter.builder("messages_sent_total")
                .description("Total messages sent through Instagram connector")
                .tag("channel", "instagram")
                .tag("status", "failed")
                .register(prometheusRegistry);
        
        // API call duration
        this.apiDuration = Timer.builder("connector_api_duration_seconds")
                .description("Duration of Instagram API calls")
                .tag("channel", "instagram")
                .register(prometheusRegistry);
        
        // Circuit breaker state gauge
        this.circuitBreakerState = new AtomicInteger(0); // Start CLOSED
        Gauge.builder("circuit_breaker_state", circuitBreakerState, AtomicInteger::get)
                .description("Circuit breaker state (0=closed, 1=open, 0.5=half_open)")
                .tag("channel", "instagram")
                .register(prometheusRegistry);
        
        // Transition counters map
        this.transitionCounters = new ConcurrentHashMap<>();
    }
    
    public static synchronized ConnectorMetricsRegistry getInstance() {
        if (instance == null) {
            instance = new ConnectorMetricsRegistry();
        }
        return instance;
    }
    
    /**
     * Record a successful message send
     */
    public void recordMessageSent() {
        messagesSentSuccess.increment();
    }
    
    /**
     * Record a failed message send
     */
    public void recordMessageFailed() {
        messagesSentFailed.increment();
    }
    
    /**
     * Record API call duration
     */
    public void recordApiCall(long durationMillis) {
        apiDuration.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * Update circuit breaker state
     * @param state "CLOSED", "OPEN", or "HALF_OPEN"
     */
    public void setCircuitBreakerState(String state) {
        switch (state.toUpperCase()) {
            case "CLOSED":
                circuitBreakerState.set(0);
                break;
            case "OPEN":
                circuitBreakerState.set(1);
                break;
            case "HALF_OPEN":
                // Use 50 to represent 0.5 in gauge (will be normalized)
                circuitBreakerState.set(50);
                break;
            default:
                System.err.println("Unknown circuit breaker state: " + state);
        }
    }
    
    /**
     * Record circuit breaker state transition
     */
    public void recordCircuitBreakerTransition(String fromState, String toState) {
        String key = fromState + "_to_" + toState;
        Counter counter = transitionCounters.computeIfAbsent(key, k ->
                Counter.builder("circuit_breaker_transitions_total")
                        .description("Circuit breaker state transitions")
                        .tag("channel", "instagram")
                        .tag("from", fromState)
                        .tag("to", toState)
                        .register(prometheusRegistry)
        );
        counter.increment();
    }
    
    /**
     * Get Prometheus registry for scraping
     */
    public PrometheusMeterRegistry getPrometheusRegistry() {
        return prometheusRegistry;
    }
}
