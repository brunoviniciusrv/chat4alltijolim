package chat4all.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.prometheus.client.exporter.HTTPServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class WebSocketGatewayMain {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketGatewayMain.class);
    
    public static void main(String[] args) {
        // Environment configuration
        int wsPort = Integer.parseInt(System.getenv().getOrDefault("WEBSOCKET_PORT", "8085"));
        int metricsPort = Integer.parseInt(System.getenv().getOrDefault("METRICS_PORT", "9095"));
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String jwtSecret = System.getenv().getOrDefault("JWT_SECRET", "chat4all-secret-key");
        
        logger.info("Starting WebSocket Gateway...");
        logger.info("WebSocket Port: {}", wsPort);
        logger.info("Metrics Port: {}", metricsPort);
        logger.info("Redis: {}:{}", redisHost, redisPort);
        
        try {
            // Initialize Prometheus metrics
            HTTPServer metricsServer = new HTTPServer(metricsPort);
            logger.info("Metrics server started on port {}", metricsPort);
            
            // Initialize WebSocket server
            NotificationWebSocketServer wsServer = new NotificationWebSocketServer(
                new InetSocketAddress(wsPort),
                jwtSecret
            );
            wsServer.setReuseAddr(true);
            wsServer.start();
            logger.info("WebSocket server started on port {}", wsPort);
            
            // Initialize Redis subscriber
            RedisNotificationSubscriber redisSubscriber = new RedisNotificationSubscriber(
                redisHost,
                redisPort,
                wsServer
            );
            
            // Start Redis subscriber in separate thread
            Thread redisThread = new Thread(redisSubscriber::start, "redis-subscriber");
            redisThread.setDaemon(false);
            redisThread.start();
            logger.info("Redis subscriber started");
            
            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down WebSocket Gateway...");
                try {
                    redisSubscriber.stop();
                    wsServer.stop(1000);
                    metricsServer.close();
                    logger.info("Shutdown complete");
                } catch (Exception e) {
                    logger.error("Error during shutdown", e);
                }
            }));
            
            logger.info("WebSocket Gateway is running");
            
        } catch (IOException e) {
            logger.error("Failed to start WebSocket Gateway", e);
            System.exit(1);
        }
    }
}
