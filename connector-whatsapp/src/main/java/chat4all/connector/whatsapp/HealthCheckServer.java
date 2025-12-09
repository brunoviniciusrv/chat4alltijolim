package chat4all.connector.whatsapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Health Check Server - HTTP endpoint for orchestration
 * 
 * Purpose:
 * - Provides health check endpoint for Docker/Kubernetes
 * - Allows monitoring systems to verify connector is running
 * 
 * Endpoint:
 * - GET /health → {"status":"UP"}
 * 
 * Educational Notes:
 * - Lightweight HTTP server (no Spring/Jersey dependencies)
 * - Uses built-in JDK HttpServer (com.sun.net.httpserver)
 * - Essential for container orchestration and monitoring
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class HealthCheckServer {
    
    private final int port;
    private HttpServer server;
    
    /**
     * Constructor
     * 
     * @param port Port to listen on (default: 8083)
     */
    public HealthCheckServer(int port) {
        this.port = port;
    }
    
    /**
     * Start the health check server
     * 
     * @throws IOException if server fails to start
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler());
        server.setExecutor(null); // Use default executor
        server.start();
        
        System.out.println("✅ Health check server started on port " + port);
        System.out.println("   Endpoint: GET /health");
        System.out.println("");
    }
    
    /**
     * Stop the health check server
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("✅ Health check server stopped");
        }
    }
    
    /**
     * Health check handler
     * 
     * Returns: {"status":"UP"}
     */
    private static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                // Return 200 OK with {"status":"UP"}
                String response = "{\"status\":\"UP\"}";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } else {
                // Method not allowed
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
}
