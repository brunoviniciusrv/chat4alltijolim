package chat4all.api.grpc;

import chat4all.api.grpc.interceptor.AuthInterceptor;
import chat4all.api.grpc.interceptor.MetricsInterceptor;
import chat4all.api.grpc.service.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class GrpcServer {
    
    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);
    private final Server server;
    
    public GrpcServer(
            int port,
            AuthServiceImpl authService,
            MessageServiceImpl messageService,
            GroupServiceImpl groupService,
            FileServiceImpl fileService,
            HealthServiceImpl healthService,
            AuthInterceptor authInterceptor,
            MetricsInterceptor metricsInterceptor) {
        
        this.server = ServerBuilder.forPort(port)
            .addService(authService)
            .addService(messageService)
            .addService(groupService)
            .addService(fileService)
            .addService(healthService)
            .addService(ProtoReflectionService.newInstance())
            .intercept(metricsInterceptor)
            .intercept(authInterceptor)
            .maxInboundMessageSize(100 * 1024 * 1024)
            .maxInboundMetadataSize(8 * 1024)
            .build();
    }
    
    public void start() throws IOException {
        server.start();
        log.info("✓ gRPC server started on port {}", server.getPort());
    }
    
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }
    
    public void stop() {
        if (server != null) {
            log.info("Stopping gRPC server...");
            server.shutdown();
            try {
                if (!server.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
            }
        }
    }
}
