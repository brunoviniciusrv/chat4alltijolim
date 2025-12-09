package chat4all.api.grpc.service;

import chat4all.api.auth.TokenGenerator;
import chat4all.api.cassandra.CassandraMessageRepository;
import chat4all.grpc.generated.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

public class AuthServiceImpl extends AuthServiceGrpc.AuthServiceImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private final TokenGenerator tokenGenerator;
    private final CassandraMessageRepository repository;
    
    public AuthServiceImpl(TokenGenerator tokenGenerator, CassandraMessageRepository repository) {
        this.tokenGenerator = tokenGenerator;
        this.repository = repository;
    }
    
    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        try {
            // Validar tamanho do username
            if (request.getUsername().length() < 3) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Username must be at least 3 characters").asRuntimeException());
                return;
            }
            
            // IMPORTANTE: Verificar se username já existe
            var existingUser = repository.getUserByUsername(request.getUsername());
            if (existingUser.isPresent()) {
                log.warn("⚠️  Registration failed: username '{}' already exists", request.getUsername());
                responseObserver.onError(Status.ALREADY_EXISTS.withDescription("Username already exists").asRuntimeException());
                return;
            }
            
            String userId = "user_" + UUID.randomUUID().toString();
            String passwordHash = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt(12));
            
            repository.createUser(userId, request.getUsername(), request.getEmail(), passwordHash);
            
            RegisterResponse response = RegisterResponse.newBuilder()
                .setUserId(userId)
                .setUsername(request.getUsername())
                .setEmail(request.getEmail())
                .setCreatedAt(System.currentTimeMillis())
                .build();
            
            log.info("✅ User registered: {} (ID: {})", request.getUsername(), userId);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("❌ Registration failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
    
    @Override
    public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        try {
            var userOpt = repository.getUserByUsername(request.getUsername());
            
            if (userOpt.isEmpty()) {
                responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Invalid credentials").asRuntimeException());
                return;
            }
            
            Map<String, Object> user = userOpt.get();
            String passwordHash = (String) user.get("password_hash");
            
            if (!BCrypt.checkpw(request.getPassword(), passwordHash)) {
                responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Invalid credentials").asRuntimeException());
                return;
            }
            
            String userId = (String) user.get("user_id");
            String accessToken = tokenGenerator.generateToken(userId);
            
            LoginResponse response = LoginResponse.newBuilder()
                .setAccessToken(accessToken)
                .setTokenType("Bearer")
                .setExpiresIn(3600)
                .setUserId(userId)
                .build();
            
            log.info("✅ User logged in: {}", request.getUsername());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("❌ Login failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
    
    @Override
    public void validateToken(ValidateTokenRequest request, StreamObserver<ValidateTokenResponse> responseObserver) {
        ValidateTokenResponse response = ValidateTokenResponse.newBuilder()
            .setValid(true)
            .setUserId("validated")
            .setUsername("")
            .setExpiresAt(0)
            .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
