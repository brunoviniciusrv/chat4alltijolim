package chat4all.api.grpc.service;

import chat4all.api.grpc.interceptor.MetricsInterceptor;
import chat4all.grpc.generated.v1.*;
import io.grpc.stub.StreamObserver;

public class HealthServiceImpl extends HealthServiceGrpc.HealthServiceImplBase {
    
    @Override
    public void check(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
        HealthCheckResponse response = HealthCheckResponse.newBuilder()
            .setStatus(HealthCheckResponse.ServingStatus.SERVING)
            .setMessage("gRPC API Service is healthy")
            .putDetails("version", "1.0.0")
            .putDetails("protocol", "gRPC")
            .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void getMetrics(MetricsRequest request, StreamObserver<MetricsResponse> responseObserver) {
        String metricsText = MetricsInterceptor.registry.scrape();
        
        MetricsResponse response = MetricsResponse.newBuilder()
            .setContentType("text/plain; version=0.0.4")
            .setMetrics(metricsText)
            .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
