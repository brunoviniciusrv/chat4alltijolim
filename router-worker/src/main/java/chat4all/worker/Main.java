package chat4all.worker;

import chat4all.worker.cassandra.CassandraConnection;
import chat4all.worker.cassandra.CassandraMessageStore;
import chat4all.worker.http.MetricsServer;
import chat4all.worker.kafka.KafkaMessageConsumer;
import chat4all.worker.processing.MessageProcessor;
import chat4all.worker.status.StatusUpdateConsumer;

/**
 * Main - Router Worker Entry Point
 * 
 * PROPÓSITO EDUCACIONAL: Event-Driven Worker Architecture
 * ==================
 * 
 * O QUE É O ROUTER WORKER?
 * - Microserviço que consome mensagens do Kafka
 * - Processa eventos (persist + update status)
 * - Executa lógica de negócio assíncrona
 * 
 * ARQUITETURA:
 * ```
 * API Service → Kafka Topic "messages" → Router Worker → Cassandra
 *               (partition by conversation_id)    ↓
 *                                        [MessageProcessor]
 *                                                 ↓
 *                                    SENT → (simulate) → DELIVERED
 * ```
 * 
 * COMPONENTES INICIALIZADOS:
 * 1. CassandraConnection: Conexão com cluster Cassandra
 * 2. CassandraMessageStore: Repository para persistência
 * 3. MessageProcessor: Lógica de negócio
 * 4. KafkaMessageConsumer: Consumidor de mensagens
 * 
 * LIFECYCLE:
 * 1. Read config from environment variables
 * 2. Initialize Cassandra connection
 * 3. Initialize Kafka consumer
 * 4. Start consuming (BLOCKING CALL)
 * 5. On shutdown: Close resources gracefully
 * 
 * HORIZONTAL SCALING:
 * - Múltiplas instâncias deste worker podem rodar simultaneamente
 * - Kafka distribui partitions entre elas (consumer group)
 * - Cada instância processa partitions diferentes
 * 
 * EXEMPLO COM 3 WORKERS:
 * ```
 * Topic "messages" (3 partitions):
 *   Partition 0 → Worker Instance 1
 *   Partition 1 → Worker Instance 2
 *   Partition 2 → Worker Instance 3
 * ```
 * 
 * Se 1 worker cair:
 * ```
 *   Partition 0 → Worker Instance 1
 *   Partition 1 → Worker Instance 1 (rebalanced)
 *   Partition 2 → Worker Instance 3
 * ```
 * 
 * @author Chat4All Educational Project
 */
public class Main {
    
    /**
     * Main entry point
     * 
     * ENVIRONMENT VARIABLES:
     * - CASSANDRA_CONTACT_POINTS: Cassandra nodes (default: "cassandra")
     * - CASSANDRA_PORT: Cassandra port (default: 9042)
     * - CASSANDRA_KEYSPACE: Keyspace (default: "chat4all")
     * - CASSANDRA_DATACENTER: Datacenter (default: "datacenter1")
     * - KAFKA_BOOTSTRAP_SERVERS: Kafka brokers (default: "kafka:9092")
     * - KAFKA_TOPIC_MESSAGES: Topic to consume (default: "messages")
     * - KAFKA_GROUP_ID: Consumer group ID (default: "router-worker-group")
     * 
     * @param args Command line arguments (unused)
     */
    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("  Chat4All Router Worker");
        System.out.println("===========================================");
        
        // Read configuration from environment
        String kafkaBootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        String kafkaTopic = System.getenv().getOrDefault("KAFKA_TOPIC_MESSAGES", "messages");
        String kafkaGroupId = System.getenv().getOrDefault("KAFKA_GROUP_ID", "router-worker-group");
        String statusTopic = System.getenv().getOrDefault("KAFKA_TOPIC_STATUS", "status-updates");
        String statusGroupId = System.getenv().getOrDefault("KAFKA_STATUS_GROUP_ID", "status-consumer-group");
        int metricsPort = Integer.parseInt(System.getenv().getOrDefault("HEALTH_PORT", "8082"));
        
        // Redis configuration for WebSocket notifications
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "redis");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        
        System.out.println("Configuration:");
        System.out.println("  Kafka: " + kafkaBootstrap);
        System.out.println("  Messages Topic: " + kafkaTopic);
        System.out.println("  Messages Group: " + kafkaGroupId);
        System.out.println("  Status Topic: " + statusTopic);
        System.out.println("  Status Group: " + statusGroupId);
        System.out.println("  Metrics Port: " + metricsPort);
        System.out.println("  Redis: " + redisHost + ":" + redisPort);
        System.out.println("===========================================\n");
        
        // Initialize Metrics Server (Phase 3: Observability)
        System.out.println("▶ Initializing metrics server...");
        MetricsServer metricsServer;
        try {
            metricsServer = new MetricsServer(metricsPort);
            metricsServer.start();
        } catch (Exception e) {
            System.err.println("✗ Failed to start metrics server: " + e.getMessage());
            throw new RuntimeException(e);
        }
        System.out.println();
        
        // Initialize Cassandra connection
        System.out.println("▶ Initializing Cassandra...");
        CassandraConnection cassandraConnection = new CassandraConnection();
        CassandraMessageStore messageStore = new CassandraMessageStore(cassandraConnection.getSession());
        System.out.println();
        
        // Initialize connector router (Phase 5: external platform routing)
        System.out.println("▶ Initializing connector router...");
        chat4all.worker.routing.ConnectorRouter connectorRouter = new chat4all.worker.routing.ConnectorRouter(kafkaBootstrap);
        System.out.println("✓ ConnectorRouter initialized\n");
        
        // Initialize Redis notification publisher (Phase 8: WebSocket notifications)
        System.out.println("▶ Initializing Redis notification publisher...");
        chat4all.worker.notifications.RedisNotificationPublisher notificationPublisher = 
            new chat4all.worker.notifications.RedisNotificationPublisher(redisHost, redisPort);
        System.out.println();
        
        // Initialize message processor
        System.out.println("▶ Initializing message processor...");
        MessageProcessor messageProcessor = new MessageProcessor(messageStore, connectorRouter, notificationPublisher);
        System.out.println("✓ MessageProcessor initialized\n");
        
        // Initialize Kafka consumer
        System.out.println("▶ Initializing Kafka consumer...");
        KafkaMessageConsumer consumer = new KafkaMessageConsumer(
            kafkaBootstrap,
            kafkaTopic,
            kafkaGroupId,
            messageProcessor
        );
        System.out.println();
        
        // Initialize Status Update Consumer (Phase 7: Status Lifecycle)
        System.out.println("▶ Initializing status update consumer...");
        StatusUpdateConsumer statusConsumer = new StatusUpdateConsumer(
            kafkaBootstrap,
            statusGroupId,
            statusTopic,
            cassandraConnection
        );
        System.out.println();
        
        // Start status consumer in separate thread
        Thread statusConsumerThread = new Thread(() -> {
            statusConsumer.run();
        }, "status-consumer-thread");
        statusConsumerThread.start();
        System.out.println("✓ Status consumer thread started\n");
        
        // Register shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n⊗ Shutdown signal received");
            System.out.println("▶ Stopping metrics server...");
            metricsServer.stop();
            System.out.println("▶ Stopping message consumer...");
            consumer.stop();
            System.out.println("▶ Stopping status consumer...");
            statusConsumer.shutdown();
            try {
                statusConsumerThread.join(5000); // Wait max 5s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("▶ Closing connector router...");
            connectorRouter.close();
            System.out.println("▶ Closing Redis notification publisher...");
            notificationPublisher.close();
            System.out.println("▶ Closing Cassandra connection...");
            cassandraConnection.close();
            System.out.println("✓ Router Worker stopped gracefully");
        }));
        
        // Start consuming (BLOCKING - runs until stop() is called)
        System.out.println("===========================================");
        System.out.println("  ✓ Router Worker Ready");
        System.out.println("  ⏳ Waiting for messages...");
        System.out.println("  Press Ctrl+C to stop");
        System.out.println("===========================================\n");
        
        consumer.startConsuming();
    }
}
