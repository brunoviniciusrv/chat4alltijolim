package chat4all.api.grpc.interceptor;

import io.grpc.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

/**
 * MetricsInterceptor - Prometheus metrics for gRPC
 */
public class MetricsInterceptor implements ServerInterceptor {
    
    public static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        
        String methodName = call.getMethodDescriptor().getFullMethodName();
        Timer.Sample sample = Timer.start(registry);
        
        Counter.builder("grpc_server_calls_total")
            .tag("method", methodName)
            .register(registry)
            .increment();
        
        ServerCall<ReqT, RespT> monitoringCall = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                sample.stop(Timer.builder("grpc_server_call_duration_seconds")
                    .tag("method", methodName)
                    .tag("status", status.getCode().name())
                    .register(registry));
                
                if (!status.isOk()) {
                    Counter.builder("grpc_server_errors_total")
                        .tag("method", methodName)
                        .tag("code", status.getCode().name())
                        .register(registry)
                        .increment();
                }
                
                super.close(status, trailers);
            }
        };
        
        return next.startCall(monitoringCall, headers);
    }
}
