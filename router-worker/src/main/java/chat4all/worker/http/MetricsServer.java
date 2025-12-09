package chat4all.worker.http;

import chat4all.worker.metrics.WorkerMetricsRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * MetricsServer - HTTP server for metrics and health checks
 * 
 * PURPOSE: Expose Prometheus metrics and health endpoints
 * Port: 8082 (configured via HEALTH_PORT env var)
 * 
 * ENDPOINTS:
 * - GET /actuator/prometheus - Prometheus metrics
 * - GET /actuator/health - Detailed health check
 * - GET /health - Simple liveness check
 * 
 * EDUCATIONAL NOTES:
 * - Separate HTTP server from main worker (non-blocking)
 * - Lightweight: only for observability, not business logic
 * - Enables Docker health checks and Prometheus scraping
 * 
 * @author Chat4All Educational Project
 */
public class MetricsServer {
    
    private final HttpServer server;
    private final WorkerMetricsRegistry metricsRegistry;
    
    public MetricsServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.metricsRegistry = WorkerMetricsRegistry.getInstance();
        
        // Register endpoints
        server.createContext("/actuator/prometheus", new MetricsHandler());
        server.createContext("/actuator/health", new HealthCheckHandler());
        server.createContext("/health", new SimpleHealthHandler());
        
        // Use default executor
        server.setExecutor(null);
        
        System.out.println("[MetricsServer] Configured on port " + port);
    }
    
    /**
     * Start metrics server
     */
    public void start() {
        server.start();
        System.out.println("[MetricsServer] Started successfully");
        System.out.println("  GET /actuator/prometheus - Prometheus metrics");
        System.out.println("  GET /actuator/health     - Health check");
        System.out.println("  GET /health              - Simple liveness");
    }
    
    /**
     * Stop metrics server
     */
    public void stop() {
        server.stop(5);
        System.out.println("[MetricsServer] Stopped");
    }
    
    /**
     * Prometheus metrics handler
     */
    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "application/json", "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String metrics = metricsRegistry.scrape();
                sendResponse(exchange, 200, "text/plain; version=0.0.4", metrics);
            } catch (Exception e) {
                System.err.println("[MetricsHandler] Error: " + e.getMessage());
                sendResponse(exchange, 500, "application/json", "{\"error\":\"Internal server error\"}");
            }
        }
    }
    
    /**
     * Detailed health check handler
     */
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "application/json", "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                // Build health response
                io.micrometer.core.instrument.Gauge lagGauge = metricsRegistry.getPrometheusRegistry()
                        .find("kafka_consumer_lag")
                        .gauge();
                
                long consumerLag = 0;
                if (lagGauge != null) {
                    consumerLag = (long) lagGauge.value();
                }
                
                // Consider unhealthy if lag > 5000
                boolean healthy = consumerLag < 5000;
                String status = healthy ? "UP" : "DEGRADED";
                int statusCode = healthy ? 200 : 503;
                
                String health = String.format(
                        "{\"status\":\"%s\",\"consumer_lag\":%d,\"threshold\":5000,\"timestamp\":\"%s\"}",
                        status,
                        consumerLag,
                        java.time.Instant.now().toString()
                );
                
                sendResponse(exchange, statusCode, "application/json", health);
            } catch (Exception e) {
                System.err.println("[HealthCheckHandler] Error: " + e.getMessage());
                sendResponse(exchange, 503, "application/json", 
                        "{\"status\":\"DOWN\",\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    /**
     * Simple liveness check handler
     */
    private class SimpleHealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, "application/json", "{\"status\":\"UP\"}");
        }
    }
    
    /**
     * Send HTTP response helper
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, body.length());
        OutputStream os = exchange.getResponseBody();
        os.write(body.getBytes());
        os.close();
    }
}
