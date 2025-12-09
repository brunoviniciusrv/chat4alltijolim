package chat4all.shared.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry Tracing Configuration
 * 
 * Implements RNF-008: Distributed Tracing
 * 
 * Features:
 * - Jaeger integration
 * - W3C Trace Context propagation
 * - Automatic span batching
 * - Service-specific tracing
 * 
 * Usage:
 * <pre>
 * OpenTelemetry openTelemetry = TracingConfig.initialize("api-service");
 * Tracer tracer = openTelemetry.getTracer("chat4all");
 * 
 * Span span = tracer.spanBuilder("operation-name").startSpan();
 * try (Scope scope = span.makeCurrent()) {
 *     // Your code here
 * } finally {
 *     span.end();
 * }
 * </pre>
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class TracingConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(TracingConfig.class);
    
    /**
     * Initialize OpenTelemetry with Jaeger exporter
     * 
     * @param serviceName The name of the service (e.g., "api-service", "router-worker")
     * @return Configured OpenTelemetry instance
     */
    public static OpenTelemetry initialize(String serviceName) {
        return initialize(serviceName, "http://localhost:14250");
    }
    
    /**
     * Initialize OpenTelemetry with custom Jaeger endpoint
     * 
     * @param serviceName The name of the service
     * @param jaegerEndpoint Jaeger gRPC endpoint (default: http://localhost:14250)
     * @return Configured OpenTelemetry instance
     */
    public static OpenTelemetry initialize(String serviceName, String jaegerEndpoint) {
        logger.info("Initializing OpenTelemetry tracing for service: {}", serviceName);
        
        // Create Jaeger exporter
        JaegerGrpcSpanExporter jaegerExporter = JaegerGrpcSpanExporter.builder()
            .setEndpoint(jaegerEndpoint)
            .setTimeout(10, TimeUnit.SECONDS)
            .build();
        
        // Create resource with service name
        Resource resource = Resource.getDefault()
            .merge(Resource.builder()
                .put(ResourceAttributes.SERVICE_NAME, serviceName)
                .put(ResourceAttributes.SERVICE_VERSION, "1.0.0")
                .put("environment", getEnvironment())
                .build());
        
        // Create tracer provider with batch processor
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(jaegerExporter)
                .setMaxQueueSize(2048)
                .setMaxExportBatchSize(512)
                .setExporterTimeout(30, TimeUnit.SECONDS)
                .setScheduleDelay(5, TimeUnit.SECONDS)
                .build())
            .setResource(resource)
            .build();
        
        // Create OpenTelemetry SDK
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down OpenTelemetry...");
            try {
                tracerProvider.close();
            } catch (Exception e) {
                logger.error("Error shutting down tracer provider", e);
            }
        }));
        
        logger.info("OpenTelemetry initialized successfully. Exporting to: {}", jaegerEndpoint);
        return openTelemetry;
    }
    
    /**
     * Get environment from system property or env var
     * Defaults to "development"
     */
    private static String getEnvironment() {
        String env = System.getProperty("environment");
        if (env == null) {
            env = System.getenv("ENVIRONMENT");
        }
        return env != null ? env : "development";
    }
    
    /**
     * Create a tracer instance
     * 
     * @param openTelemetry OpenTelemetry instance
     * @param instrumentationName Name of the instrumentation (e.g., "chat4all.api")
     * @return Tracer instance
     */
    public static Tracer getTracer(OpenTelemetry openTelemetry, String instrumentationName) {
        return openTelemetry.getTracer(instrumentationName, "1.0.0");
    }
}
