package chat4all.worker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * WorkerMetricsRegistry - Centralized metrics for Router Worker
 * 
 * PURPOSE: Expose Prometheus metrics for worker observability
 * Implements: Seção 2.4 (Observabilidade) + Seção 7 (Escalabilidade) do esqueleto.md
 * 
 * METRICS EXPOSED:
 * - messages_consumed_total{topic, partition} - Total consumed from Kafka
 * - messages_processed_total{status} - Messages processed (SENT, DELIVERED, FAILED)
 * - messages_failed_total{reason} - Processing failures
 * - kafka_consumer_lag{topic, partition} - Consumer lag
 * - processing_duration_seconds - Message processing time
 * - cassandra_write_duration_seconds - Cassandra write latency
 * 
 * EDUCATIONAL NOTES:
 * - Consumer lag: Critical metric for scalability validation
 * - Processing duration: Validates latency requirements (< 200ms)
 * - Status tracking: SENT → DELIVERED → READ lifecycle
 * 
 * @author Chat4All Educational Project
 */
public class WorkerMetricsRegistry {
    
    private static WorkerMetricsRegistry instance;
    private final PrometheusMeterRegistry prometheusRegistry;
    
    // Kafka metrics
    private final Counter messagesConsumedTotal;
    private final AtomicLong kafkaConsumerLag;
    
    // Processing metrics
    private final Counter messagesProcessedTotal;
    private final Counter messagesFailedTotal;
    private final Timer processingDuration;
    
    // Cassandra metrics
    private final Timer cassandraWriteDuration;
    
    /**
     * Private constructor (Singleton pattern)
     */
    private WorkerMetricsRegistry() {
        // Create Prometheus registry
        this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        
        // Register JVM metrics
        new ClassLoaderMetrics().bindTo(prometheusRegistry);
        new JvmMemoryMetrics().bindTo(prometheusRegistry);
        new JvmGcMetrics().bindTo(prometheusRegistry);
        new JvmThreadMetrics().bindTo(prometheusRegistry);
        new ProcessorMetrics().bindTo(prometheusRegistry);
        
        // Initialize Kafka metrics
        this.messagesConsumedTotal = Counter.builder("messages_consumed_total")
                .description("Total messages consumed from Kafka")
                .tag("service", "router-worker")
                .register(prometheusRegistry);
        
        this.kafkaConsumerLag = new AtomicLong(0);
        Gauge.builder("kafka_consumer_lag", kafkaConsumerLag, AtomicLong::get)
                .description("Consumer lag (messages behind)")
                .tag("service", "router-worker")
                .register(prometheusRegistry);
        
        // Initialize processing metrics
        this.messagesProcessedTotal = Counter.builder("messages_processed_total")
                .description("Total messages processed")
                .tag("service", "router-worker")
                .register(prometheusRegistry);
        
        this.messagesFailedTotal = Counter.builder("messages_failed_total")
                .description("Total processing failures")
                .tag("service", "router-worker")
                .register(prometheusRegistry);
        
        this.processingDuration = Timer.builder("processing_duration_seconds")
                .description("Message processing duration")
                .tag("service", "router-worker")
                .register(prometheusRegistry);
        
        // Initialize Cassandra metrics
        this.cassandraWriteDuration = Timer.builder("cassandra_write_duration_seconds")
                .description("Cassandra write latency")
                .tag("service", "router-worker")
                .register(prometheusRegistry);
    }
    
    /**
     * Get singleton instance
     * 
     * @return WorkerMetricsRegistry instance
     */
    public static synchronized WorkerMetricsRegistry getInstance() {
        if (instance == null) {
            instance = new WorkerMetricsRegistry();
        }
        return instance;
    }
    
    /**
     * Get Prometheus registry (for scraping endpoint)
     * 
     * @return PrometheusMeterRegistry
     */
    public PrometheusMeterRegistry getPrometheusRegistry() {
        return prometheusRegistry;
    }
    
    /**
     * Get MeterRegistry (for custom metrics)
     * 
     * @return MeterRegistry
     */
    public MeterRegistry getMeterRegistry() {
        return prometheusRegistry;
    }
    
    /**
     * Record message consumed from Kafka
     * 
     * @param topic Kafka topic
     * @param partition Partition number
     */
    public void recordMessageConsumed(String topic, int partition) {
        messagesConsumedTotal.increment();
        
        Counter.builder("messages_consumed_by_partition_total")
                .description("Messages consumed by partition")
                .tag("topic", topic)
                .tag("partition", String.valueOf(partition))
                .register(prometheusRegistry)
                .increment();
    }
    
    /**
     * Record message processed
     * 
     * @param status Message status (SENT, DELIVERED, FAILED)
     * @param durationMs Processing duration in milliseconds
     */
    public void recordMessageProcessed(String status, long durationMs) {
        messagesProcessedTotal.increment();
        
        Counter.builder("messages_processed_by_status_total")
                .description("Messages processed by status")
                .tag("status", status)
                .register(prometheusRegistry)
                .increment();
        
        processingDuration.record(java.time.Duration.ofMillis(durationMs));
    }
    
    /**
     * Record processing failure
     * 
     * @param reason Failure reason (cassandra_error, kafka_error, invalid_message, etc.)
     */
    public void recordMessageFailed(String reason) {
        messagesFailedTotal.increment();
        
        Counter.builder("messages_failed_by_reason_total")
                .description("Processing failures by reason")
                .tag("reason", reason)
                .register(prometheusRegistry)
                .increment();
    }
    
    /**
     * Record Cassandra write
     * 
     * @param durationMs Write duration in milliseconds
     * @param success Whether write succeeded
     */
    public void recordCassandraWrite(long durationMs, boolean success) {
        cassandraWriteDuration.record(java.time.Duration.ofMillis(durationMs));
        
        Counter.builder("cassandra_writes_total")
                .description("Total Cassandra writes")
                .tag("success", String.valueOf(success))
                .register(prometheusRegistry)
                .increment();
    }
    
    /**
     * Update consumer lag metric
     * 
     * @param lag Current lag (messages behind)
     */
    public void updateConsumerLag(long lag) {
        kafkaConsumerLag.set(lag);
    }
    
    /**
     * Record consumer lag by partition
     * 
     * @param topic Kafka topic
     * @param partition Partition number
     * @param lag Lag for this partition
     */
    public void recordConsumerLagByPartition(String topic, int partition, long lag) {
        Gauge.builder("kafka_consumer_lag_by_partition", () -> lag)
                .description("Consumer lag by partition")
                .tag("topic", topic)
                .tag("partition", String.valueOf(partition))
                .register(prometheusRegistry);
    }
    
    /**
     * Scrape metrics in Prometheus format
     * 
     * @return Metrics in Prometheus exposition format
     */
    public String scrape() {
        return prometheusRegistry.scrape();
    }
}
