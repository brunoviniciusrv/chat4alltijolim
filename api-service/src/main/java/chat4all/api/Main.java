package chat4all.api;

import chat4all.api.auth.JwtAuthenticator;
import chat4all.api.auth.TokenGenerator;
import chat4all.api.cassandra.CassandraConnection;
import chat4all.api.cassandra.CassandraMessageRepository;
import chat4all.api.grpc.GrpcServer;
import chat4all.api.grpc.interceptor.AuthInterceptor;
import chat4all.api.grpc.interceptor.MetricsInterceptor;
import chat4all.api.grpc.service.*;
import chat4all.api.http.RestGateway;
import chat4all.api.kafka.MessageProducer;
import chat4all.api.storage.MinioFileStorage;
import chat4all.api.metrics.PrometheusMetricsServer;
import chat4all.shared.tracing.TracingConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

/**
 * Main - gRPC API Service Entry Point
 * 
 * WHY gRPC INSTEAD OF HTTP REST?
 * ===============================
 * 
 * PERFORMANCE BENEFITS:
 * - 4x throughput: HTTP/1.1 (~5k req/s) → gRPC (~20k req/s)
 * - 5x lower latency: HTTP (~50ms) → gRPC (~10ms)
 * - 70% smaller payloads: JSON → Protocol Buffers (binary)
 * - HTTP/2 multiplexing: Multiple streams over single TCP connection
 * 
 * STREAMING CAPABILITIES:
 * - Bidirectional streaming: Real-time chat (MessageService.StreamMessages)
 * - Client streaming: File uploads (FileService.UploadFile)
 * - Server streaming: File downloads (FileService.DownloadFile)
 * - Not possible with traditional HTTP REST
 * 
 * TYPE SAFETY:
 * - Protocol Buffers: Compile-time validation
 * - Generated code: Type-safe clients and servers
 * - No runtime JSON parsing errors
 * 
 * ARCHITECTURE:
 * ```
 * Client → gRPC Server → Interceptors → Service → Kafka → Router Worker
 *             ↓                           ↓
 *       [Port 9090]                  Cassandra (read/write)
 *                                    MinIO (file storage)
 * 
 * Interceptor Chain:
 * 1. MetricsInterceptor: Prometheus metrics
 * 2. AuthInterceptor: JWT validation → Context.USER_ID
 * 
 * Services:
 * - AuthService: Register, Login, ValidateToken
 * - MessageService: SendMessage, GetMessages, MarkAsRead, StreamMessages
 * - GroupService: CreateGroup, GetGroup, ListUserGroups
 * - FileService: UploadFile (streaming), DownloadFile (streaming)
 * - HealthService: Check, GetMetrics
 * ```
 * 
 * SERVER CONFIG:
 * - Port: 9090 (default) or GRPC_PORT env var
 * - Max message size: 100MB (for file uploads)
 * - Max metadata size: 8KB (for JWT tokens)
 * - Reflection: Enabled (for grpcurl debugging)
 * 
 * LIFECYCLE:
 * 1. Read config from environment variables
 * 2. Create dependencies (Cassandra, Kafka, MinIO, Auth)
 * 3. Create gRPC service implementations
 * 4. Create GrpcServer and register services
 * 5. Start server (blocking)
 * 6. Shutdown hook for graceful cleanup
 * 
 * @author Chat4All Educational Project
 */
public class Main {
    
    /**
     * Main entry point
     * 
     * ENVIRONMENT VARIABLES:
     * - GRPC_PORT: gRPC server port (default: 9090)
     * - JWT_SECRET: Secret key for JWT signing (default: "dev-secret-change-in-production")
     * - KAFKA_BOOTSTRAP_SERVERS: Kafka brokers (default: "kafka:9092")
     * - KAFKA_TOPIC_MESSAGES: Kafka topic for messages (default: "messages")
     * - CASSANDRA_CONTACT_POINTS: Cassandra hosts (default: "cassandra")
     * - CASSANDRA_PORT: Cassandra port (default: 9042)
     * - CASSANDRA_KEYSPACE: Cassandra keyspace (default: "chat4all")
     * - CASSANDRA_DATACENTER: Cassandra datacenter (default: "dc1")
     * - MINIO_ENDPOINT: MinIO endpoint (default: "http://minio:9000")
     * - MINIO_ACCESS_KEY: MinIO access key (default: "minioadmin")
     * - MINIO_SECRET_KEY: MinIO secret key (default: "minioadmin")
     * 
     * @param args Command line arguments (unused)
     * @throws Exception if server fails to start
     */
    public static void main(String[] args) throws Exception {
        // 1. Read configuration from environment
        int port = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "9090"));
        String jwtSecret = System.getenv().getOrDefault("JWT_SECRET", "dev-secret-change-in-production");
        String kafkaBootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        String kafkaTopic = System.getenv().getOrDefault("KAFKA_TOPIC_MESSAGES", "messages");
        String minioEndpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://minio:9000");
        String minioAccessKey = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin");
        String minioSecretKey = System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin");
        
        System.out.println("===========================================");
        System.out.println("  Chat4All gRPC API Service");
        System.out.println("===========================================");
        System.out.println("Port: " + port);
        System.out.println("Protocol: gRPC (HTTP/2 + Protobuf)");
        System.out.println("Kafka: " + kafkaBootstrap);
        System.out.println("Topic: " + kafkaTopic);
        System.out.println("MinIO: " + minioEndpoint);
        System.out.println("===========================================");
        
        // 2. Initialize Prometheus metrics server (RNF-002)
        int metricsPort = Integer.parseInt(System.getenv().getOrDefault("METRICS_PORT", "8080"));
        PrometheusMetricsServer metricsServer = new PrometheusMetricsServer(metricsPort);
        metricsServer.start();
        System.out.println("✓ Prometheus metrics endpoint: http://localhost:" + metricsPort + "/metrics");
        
        // 3. Initialize OpenTelemetry tracing (RNF-008)
        String jaegerEndpoint = System.getenv().getOrDefault("JAEGER_ENDPOINT", "http://jaeger:14250");
        OpenTelemetry openTelemetry = TracingConfig.initialize("api-service", jaegerEndpoint);
        Tracer tracer = TracingConfig.getTracer(openTelemetry, "chat4all.api");
        System.out.println("✓ OpenTelemetry initialized - Jaeger: " + jaegerEndpoint);
        
        // 4. Create dependencies
        TokenGenerator tokenGenerator = new TokenGenerator(jwtSecret);
        JwtAuthenticator jwtAuthenticator = new JwtAuthenticator(jwtSecret);
        MessageProducer messageProducer = new MessageProducer(kafkaBootstrap, kafkaTopic);
        
        // Cassandra connection for queries
        CassandraConnection cassandraConnection = new CassandraConnection();
        CassandraMessageRepository messageRepository = new CassandraMessageRepository(cassandraConnection.getSession());
        
        // MinIO file storage
        MinioFileStorage fileStorage = new MinioFileStorage(minioEndpoint, minioAccessKey, minioSecretKey);
        
        // 6. Create interceptors (moved up to use in services)
        AuthInterceptor authInterceptor = new AuthInterceptor(jwtAuthenticator);
        MetricsInterceptor metricsInterceptor = new MetricsInterceptor();
        
        // 5. Create gRPC service implementations with metrics
        AuthServiceImpl authService = new AuthServiceImpl(tokenGenerator, messageRepository);
        MessageServiceImpl messageService = new MessageServiceImpl(messageProducer, messageRepository, tracer, metricsServer);
        GroupServiceImpl groupService = new GroupServiceImpl(messageRepository, authInterceptor);
        FileServiceImpl fileService = new FileServiceImpl(fileStorage, tracer);
        HealthServiceImpl healthService = new HealthServiceImpl();
        
        // 7. Create and start gRPC server
        GrpcServer grpcServer = new GrpcServer(
            port,
            authService,
            messageService,
            groupService,
            fileService,
            healthService,
            authInterceptor,
            metricsInterceptor
        );
        
        // 7.5. Start HTTP REST Gateway for web interface
        int httpPort = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8081"));
        RestGateway restGateway = new RestGateway(httpPort, authService, messageService, messageRepository, fileStorage, messageProducer);
        restGateway.start();
        System.out.println("✓ HTTP REST Gateway started on port " + httpPort);
        
        // 8. Register shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down gRPC API service...");
            metricsServer.setUpStatus(false);
            restGateway.stop();
            grpcServer.stop();
            messageProducer.close();
            cassandraConnection.close();
            metricsServer.stop();
            System.out.println("gRPC API service stopped.");
        }));
        
        // 9. Start server (blocking)
        grpcServer.start();
        System.out.println("✓ gRPC API service started on port " + port);
        System.out.println("✓ Services:");
        System.out.println("  chat4all.AuthService");
        System.out.println("    - Register(RegisterRequest) → RegisterResponse");
        System.out.println("    - Login(LoginRequest) → LoginResponse");
        System.out.println("    - ValidateToken(ValidateTokenRequest) → ValidateTokenResponse");
        System.out.println("  chat4all.MessageService");
        System.out.println("    - SendMessage(SendMessageRequest) → SendMessageResponse");
        System.out.println("    - GetMessages(GetMessagesRequest) → GetMessagesResponse");
        System.out.println("    - MarkAsRead(MarkAsReadRequest) → MarkAsReadResponse");
        System.out.println("    - StreamMessages(stream SendMessageRequest) → stream MessageNotification");
        System.out.println("  chat4all.GroupService");
        System.out.println("    - CreateGroup(CreateGroupRequest) → CreateGroupResponse");
        System.out.println("    - GetGroup(GetGroupRequest) → GetGroupResponse");
        System.out.println("    - ListUserGroups(ListUserGroupsRequest) → ListUserGroupsResponse");
        System.out.println("  chat4all.FileService");
        System.out.println("    - UploadFile(stream FileChunk) → UploadFileResponse");
        System.out.println("    - DownloadFile(DownloadFileRequest) → stream FileChunk");
        System.out.println("  chat4all.HealthService");
        System.out.println("    - Check(HealthCheckRequest) → HealthCheckResponse");
        System.out.println("    - GetMetrics(MetricsRequest) → MetricsResponse");
        System.out.println("\nDebug with: grpcurl -plaintext localhost:" + port + " list");
        System.out.println("Press Ctrl+C to stop.");
        
        // Block until shutdown
        grpcServer.blockUntilShutdown();
    }
}
