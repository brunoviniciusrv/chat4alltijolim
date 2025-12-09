package chat4all.websocket;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NotificationWebSocketServer extends WebSocketServer {
    private static final Logger logger = LoggerFactory.getLogger(NotificationWebSocketServer.class);
    
    // userId -> WebSocket connection
    private final Map<String, WebSocket> connections = new ConcurrentHashMap<>();
    
    // WebSocket -> userId (reverse mapping)
    private final Map<WebSocket, String> connectionToUser = new ConcurrentHashMap<>();
    
    private final String jwtSecret;
    private final JWTVerifier jwtVerifier;
    
    // Prometheus metrics
    private static final Gauge activeConnections = Gauge.build()
        .name("websocket_connections_active")
        .help("Number of active WebSocket connections")
        .register();
    
    private static final Counter notificationsSent = Counter.build()
        .name("notifications_sent_total")
        .help("Total number of notifications sent via WebSocket")
        .register();
    
    private static final Counter connectionErrors = Counter.build()
        .name("websocket_connection_errors_total")
        .help("Total number of WebSocket connection errors")
        .labelNames("error_type")
        .register();
    
    public NotificationWebSocketServer(InetSocketAddress address, String jwtSecret) {
        super(address);
        this.jwtSecret = jwtSecret;
        this.jwtVerifier = JWT.require(Algorithm.HMAC256(jwtSecret))
            // Don't require issuer - API doesn't set it
            //.withIssuer("chat4all")
            .build();
        logger.info("WebSocket server initialized");
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String uri = handshake.getResourceDescriptor();
        logger.info("New connection from {}: {}", conn.getRemoteSocketAddress(), uri);
        
        try {
            // Extract token from query parameter: /notifications?token=xxx
            String token = extractToken(uri);
            if (token == null) {
                logger.warn("No token provided in connection");
                conn.close(1008, "Token required");
                connectionErrors.labels("missing_token").inc();
                return;
            }
            
            // Verify JWT and extract userId
            DecodedJWT jwt = jwtVerifier.verify(token);
            // Try user_id claim first (if present), fallback to sub claim
            String userId = jwt.getClaim("user_id").asString();
            if (userId == null || userId.isEmpty()) {
                userId = jwt.getSubject(); // Fallback to "sub" claim
            }
            
            if (userId == null || userId.isEmpty()) {
                logger.warn("Invalid token: no user_id or sub claim");
                conn.close(1008, "Invalid token");
                connectionErrors.labels("invalid_token").inc();
                return;
            }
            
            // Register connection
            WebSocket oldConnection = connections.put(userId, conn);
            connectionToUser.put(conn, userId);
            
            // Close old connection if exists (same user from different device)
            if (oldConnection != null && oldConnection.isOpen()) {
                logger.info("Closing old connection for user {}", userId);
                oldConnection.close(1000, "New connection established");
            }
            
            activeConnections.inc();
            logger.info("User {} connected successfully. Total connections: {}", 
                userId, connections.size());
            
            // Send connection confirmation
            JSONObject confirmMsg = new JSONObject();
            confirmMsg.put("type", "connected");
            confirmMsg.put("userId", userId);
            confirmMsg.put("timestamp", System.currentTimeMillis());
            conn.send(confirmMsg.toString());
            
        } catch (JWTVerificationException e) {
            logger.warn("JWT verification failed: {}", e.getMessage());
            conn.close(1008, "Invalid token");
            connectionErrors.labels("jwt_verification_failed").inc();
        } catch (Exception e) {
            logger.error("Error handling new connection", e);
            conn.close(1011, "Internal error");
            connectionErrors.labels("internal_error").inc();
        }
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String userId = connectionToUser.remove(conn);
        if (userId != null) {
            connections.remove(userId);
            activeConnections.dec();
            logger.info("User {} disconnected. Code: {}, Reason: {}. Total connections: {}", 
                userId, code, reason, connections.size());
        }
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        // We don't expect clients to send messages, but handle ping/pong if needed
        String userId = connectionToUser.get(conn);
        logger.debug("Received message from user {}: {}", userId, message);
        
        try {
            JSONObject msg = new JSONObject(message);
            if ("ping".equals(msg.optString("type"))) {
                JSONObject pong = new JSONObject();
                pong.put("type", "pong");
                pong.put("timestamp", System.currentTimeMillis());
                conn.send(pong.toString());
            }
        } catch (Exception e) {
            logger.warn("Error processing message from user {}", userId, e);
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        String userId = connectionToUser.get(conn);
        logger.error("WebSocket error for user {}", userId, ex);
        connectionErrors.labels("websocket_error").inc();
    }
    
    @Override
    public void onStart() {
        logger.info("WebSocket server started successfully");
        setConnectionLostTimeout(30); // 30 seconds timeout for ping/pong
    }
    
    /**
     * Send notification to specific user
     */
    public void sendNotificationToUser(String userId, String notificationJson) {
        WebSocket conn = connections.get(userId);
        if (conn != null && conn.isOpen()) {
            try {
                conn.send(notificationJson);
                notificationsSent.inc();
                logger.debug("Notification sent to user {}", userId);
            } catch (Exception e) {
                logger.error("Failed to send notification to user {}", userId, e);
                connectionErrors.labels("send_failed").inc();
            }
        } else {
            logger.debug("User {} not connected, notification not sent", userId);
        }
    }
    
    /**
     * Extract token from URI query string
     */
    private String extractToken(String uri) {
        if (uri == null || !uri.contains("token=")) {
            return null;
        }
        
        try {
            String[] parts = uri.split("token=");
            if (parts.length < 2) {
                return null;
            }
            
            String token = parts[1];
            // Remove any additional query parameters
            int ampIndex = token.indexOf('&');
            if (ampIndex > 0) {
                token = token.substring(0, ampIndex);
            }
            
            return token;
        } catch (Exception e) {
            logger.warn("Error extracting token from URI", e);
            return null;
        }
    }
    
    /**
     * Get number of active connections
     */
    public int getConnectionCount() {
        return connections.size();
    }
}
