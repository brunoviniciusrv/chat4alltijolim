package chat4all.api.grpc.interceptor;

import chat4all.api.auth.JwtAuthenticator;
import io.grpc.*;

/**
 * AuthInterceptor - JWT authentication for gRPC
 */
public class AuthInterceptor implements ServerInterceptor {
    
    public static final Context.Key<String> USER_ID = Context.key("userId");
    public static final Context.Key<String> USERNAME = Context.key("username");
    
    private static final String[] PUBLIC_METHODS = {
        "chat4all.v1.AuthService/Register",
        "chat4all.v1.AuthService/Login",
        "chat4all.v1.HealthService/Check",
        "chat4all.v1.HealthService/GetMetrics",
        "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo"
    };
    
    private final JwtAuthenticator jwtAuthenticator;
    
    public AuthInterceptor(JwtAuthenticator jwtAuthenticator) {
        this.jwtAuthenticator = jwtAuthenticator;
    }
    
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        
        String methodName = call.getMethodDescriptor().getFullMethodName();
        
        // Skip auth for public methods
        for (String publicMethod : PUBLIC_METHODS) {
            if (methodName.equals(publicMethod)) {
                return next.startCall(call, headers);
            }
        }
        
        // Extract authorization header
        String authHeader = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid authorization header"), new Metadata());
            return new ServerCall.Listener<ReqT>() {};
        }
        
        String token = authHeader.substring(7);
        
        try {
            // Validate token (returns userId)
            String userId = jwtAuthenticator.validateToken(token);
            
            // Populate context
            Context context = Context.current()
                .withValue(USER_ID, userId)
                .withValue(USERNAME, "user");
            
            return Contexts.interceptCall(context, call, headers, next);
            
        } catch (Exception e) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid token: " + e.getMessage()), new Metadata());
            return new ServerCall.Listener<ReqT>() {};
        }
    }
}
