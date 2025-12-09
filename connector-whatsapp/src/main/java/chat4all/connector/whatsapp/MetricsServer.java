package chat4all.connector.whatsapp;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * HTTP server for exposing Prometheus metrics and health checks.
 * 
 * Endpoints:
 * - GET /actuator/prometheus - Prometheus metrics in text format
 * - GET /actuator/health - Detailed health status (circuit breaker, message count)
 * - GET /health - Simple health check (200 OK)
 * 
 * Port: 8083 (configured in docker-compose.yml and prometheus.yml)
 */
public class MetricsServer {
    
    private final HttpServer server;
    private final ConnectorMetricsRegistry metricsRegistry;
    
    public MetricsServer(int port) throws IOException {
        this.metricsRegistry = ConnectorMetricsRegistry.getInstance();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Register handlers
        server.createContext("/actuator/prometheus", new PrometheusHandler());
        server.createContext("/actuator/health", new HealthHandler());
        server.createContext("/health", new SimpleHealthHandler());
        
        server.setExecutor(null); // Use default executor
    }
    
    public void start() {
        server.start();
        System.out.println("✓ Metrics server started on port " + server.getAddress().getPort());
        System.out.println("  - Prometheus metrics: http://localhost:" + server.getAddress().getPort() + "/actuator/prometheus");
        System.out.println("  - Health check: http://localhost:" + server.getAddress().getPort() + "/actuator/health");
    }
    
    public void stop() {
        server.stop(0);
        System.out.println("✓ Metrics server stopped");
    }
    
    /**
     * Handler for /actuator/prometheus endpoint
     * Returns metrics in Prometheus text format
     */
    private class PrometheusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            // Scrape metrics from registry
            String metrics = metricsRegistry.getPrometheusRegistry().scrape();
            byte[] response = metrics.getBytes("UTF-8");
            
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }
    
    /**
     * Handler for /actuator/health endpoint
     * Returns detailed health status
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            try {
                // Build health response
                io.micrometer.core.instrument.Counter successCounter = metricsRegistry.getPrometheusRegistry()
                        .find("messages_sent_total")
                        .tag("status", "success")
                        .counter();
                
                io.micrometer.core.instrument.Counter failedCounter = metricsRegistry.getPrometheusRegistry()
                        .find("messages_sent_total")
                        .tag("status", "failed")
                        .counter();
                
                long successCount = 0;
                long failedCount = 0;
                if (successCounter != null) {
                    successCount = (long) successCounter.count();
                }
                if (failedCounter != null) {
                    failedCount = (long) failedCounter.count();
                }
                
                // Simple health logic: UP if circuit breaker is not OPEN
                boolean healthy = metricsRegistry.getPrometheusRegistry()
                        .find("circuit_breaker_state")
                        .gauge() == null || 
                        metricsRegistry.getPrometheusRegistry()
                        .find("circuit_breaker_state")
                        .gauge()
                        .value() < 1.0;
                
                String status = healthy ? "UP" : "DOWN";
                
                String json = String.format(
                    "{\"status\":\"%s\",\"components\":{\"whatsapp\":{\"status\":\"%s\",\"details\":{\"messages_sent\":%d,\"messages_failed\":%d}}}}",
                    status, status, successCount, failedCount
                );
                
                byte[] response = json.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(healthy ? 200 : 503, response.length);
                
                OutputStream os = exchange.getResponseBody();
                os.write(response);
                os.close();
                
            } catch (Exception e) {
                String errorJson = "{\"status\":\"DOWN\",\"error\":\"" + e.getMessage() + "\"}";
                byte[] response = errorJson.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, response.length);
                
                OutputStream os = exchange.getResponseBody();
                os.write(response);
                os.close();
            }
        }
    }
    
    /**
     * Handler for /health endpoint
     * Simple 200 OK response for basic liveness check
     */
    private class SimpleHealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}
