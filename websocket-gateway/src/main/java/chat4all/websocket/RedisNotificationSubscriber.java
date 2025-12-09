package chat4all.websocket;

import io.prometheus.client.Counter;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

public class RedisNotificationSubscriber {
    private static final Logger logger = LoggerFactory.getLogger(RedisNotificationSubscriber.class);
    
    private final JedisPool jedisPool;
    private final NotificationWebSocketServer wsServer;
    private volatile boolean running = false;
    private Thread subscriberThread;
    
    private static final String NOTIFICATION_PATTERN = "notifications:*";
    
    // Prometheus metrics
    private static final Counter redisEventsConsumed = Counter.build()
        .name("redis_events_consumed_total")
        .help("Total number of events consumed from Redis Pub/Sub")
        .register();
    
    private static final Counter redisErrors = Counter.build()
        .name("redis_errors_total")
        .help("Total number of Redis errors")
        .labelNames("error_type")
        .register();
    
    public RedisNotificationSubscriber(String redisHost, int redisPort, NotificationWebSocketServer wsServer) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        
        this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort);
        this.wsServer = wsServer;
        
        logger.info("Redis subscriber initialized: {}:{}", redisHost, redisPort);
    }
    
    public void start() {
        if (running) {
            logger.warn("Redis subscriber already running");
            return;
        }
        
        running = true;
        logger.info("Starting Redis subscriber...");
        
        // Create JedisPubSub handler
        JedisPubSub jedisPubSub = new JedisPubSub() {
            @Override
            public void onPMessage(String pattern, String channel, String message) {
                try {
                    // Extract userId from channel name: notifications:user123 -> user123
                    String userId = channel.substring("notifications:".length());
                    
                    logger.debug("Received notification for user {} from Redis", userId);
                    redisEventsConsumed.inc();
                    
                    // Parse message to ensure it's valid JSON
                    JSONObject notification = new JSONObject(message);
                    
                    // Add notification type if not present
                    if (!notification.has("type")) {
                        notification.put("type", "notification");
                    }
                    
                    // Add timestamp if not present
                    if (!notification.has("timestamp")) {
                        notification.put("timestamp", System.currentTimeMillis());
                    }
                    
                    // Forward to WebSocket server
                    wsServer.sendNotificationToUser(userId, notification.toString());
                    
                } catch (Exception e) {
                    logger.error("Error processing Redis message from channel {}", channel, e);
                    redisErrors.labels("message_processing").inc();
                }
            }
            
            @Override
            public void onPSubscribe(String pattern, int subscribedChannels) {
                logger.info("Subscribed to Redis pattern: {} (total subscriptions: {})", 
                    pattern, subscribedChannels);
            }
            
            @Override
            public void onPUnsubscribe(String pattern, int subscribedChannels) {
                logger.info("Unsubscribed from Redis pattern: {} (remaining subscriptions: {})", 
                    pattern, subscribedChannels);
            }
        };
        
        // Subscribe in loop with reconnection logic
        while (running) {
            try (Jedis jedis = jedisPool.getResource()) {
                logger.info("Subscribing to Redis pattern: {}", NOTIFICATION_PATTERN);
                
                // This call blocks until unsubscribe or error
                jedis.psubscribe(jedisPubSub, NOTIFICATION_PATTERN);
                
            } catch (Exception e) {
                if (running) {
                    logger.error("Redis subscription error, will retry in 5 seconds", e);
                    redisErrors.labels("connection").inc();
                    
                    try {
                        Thread.sleep(5000); // Wait before reconnecting
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        logger.info("Redis subscriber stopped");
    }
    
    public void stop() {
        logger.info("Stopping Redis subscriber...");
        running = false;
        
        try {
            jedisPool.close();
        } catch (Exception e) {
            logger.error("Error closing Redis pool", e);
        }
    }
}
