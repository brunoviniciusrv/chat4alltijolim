package chat4all.connector.whatsapp;

/**
 * WhatsApp Connector Mock - Entry Point
 * 
 * Purpose:
 * - Simulates WhatsApp Business API integration
 * - Consumes messages from Kafka topic "whatsapp-outbound"
 * - Publishes delivery confirmations to "status-updates" topic
 * 
 * Educational Notes:
 * - Demonstrates connector/plugin pattern for multi-platform messaging
 * - Shows async communication via Kafka (decoupled from main system)
 * - Implements health check endpoint for orchestration
 * 
 * Architecture:
 * - Standalone service (separate JVM from api-service and router-worker)
 * - Scales independently (can run multiple instances with consumer groups)
 * - Fault-tolerant: if connector crashes, messages remain in Kafka
 * 
 * Flow:
 * 1. Router-worker publishes message to "whatsapp-outbound" topic
 * 2. WhatsAppConnector consumes message
 * 3. Simulates API call to WhatsApp Business API (Thread.sleep)
 * 4. Publishes DELIVERED status to "status-updates" topic
 * 5. Status consumer updates message status in Cassandra
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class Main {
    
    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("  WhatsApp Connector Mock - Starting");
        System.out.println("===========================================");
        System.out.println("");
        
        // Read configuration from environment variables
        String kafkaBootstrapServers = System.getenv().getOrDefault(
            "KAFKA_BOOTSTRAP_SERVERS", 
            "localhost:9092"
        );
        
        String consumerGroupId = System.getenv().getOrDefault(
            "KAFKA_GROUP_ID", 
            "whatsapp-connector-group"
        );
        
        String inboundTopic = System.getenv().getOrDefault(
            "KAFKA_TOPIC_INBOUND", 
            "whatsapp-outbound"
        );
        
        String statusTopic = System.getenv().getOrDefault(
            "KAFKA_TOPIC_STATUS", 
            "status-updates"
        );
        
        int healthPort = Integer.parseInt(
            System.getenv().getOrDefault("HEALTH_PORT", "8083")
        );
        
        System.out.println("Configuration:");
        System.out.println("  Kafka Bootstrap Servers: " + kafkaBootstrapServers);
        System.out.println("  Consumer Group ID: " + consumerGroupId);
        System.out.println("  Inbound Topic: " + inboundTopic);
        System.out.println("  Status Topic: " + statusTopic);
        System.out.println("  Health Check Port: " + healthPort);
        System.out.println("");
        
        // Create status publisher (for publishing DELIVERED events)
        StatusPublisher statusPublisher = new StatusPublisher(
            kafkaBootstrapServers,
            statusTopic
        );
        
        // Create WhatsApp connector
        WhatsAppConnector connector = new WhatsAppConnector(
            kafkaBootstrapServers,
            consumerGroupId,
            inboundTopic,
            statusPublisher
        );
        
        // Start health check server in background thread
        HealthCheckServer healthServer = new HealthCheckServer(healthPort);
        Thread healthThread = new Thread(() -> {
            try {
                healthServer.start();
            } catch (Exception e) {
                System.err.println("❌ Failed to start health check server: " + e.getMessage());
                e.printStackTrace();
            }
        });
        healthThread.setDaemon(false);
        healthThread.start();
        
        // Start metrics server in background thread
        MetricsServer metricsServer = null;
        try {
            metricsServer = new MetricsServer(healthPort);
            metricsServer.start();
        } catch (Exception e) {
            System.err.println("❌ Failed to start metrics server: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Final reference for shutdown hook
        final MetricsServer finalMetricsServer = metricsServer;
        
        // Shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("");
            System.out.println("🛑 Shutdown signal received. Stopping connector...");
            connector.stop();
            statusPublisher.close();
            healthServer.stop();
            if (finalMetricsServer != null) {
                finalMetricsServer.stop();
            }
            System.out.println("✅ WhatsApp connector stopped gracefully");
        }));
        
        // Run connector (blocks until stopped)
        connector.run();
    }
}
