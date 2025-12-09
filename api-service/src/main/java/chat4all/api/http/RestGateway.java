package chat4all.api.http;

import chat4all.api.cassandra.CassandraMessageRepository;
import chat4all.api.grpc.service.*;
import chat4all.api.storage.MinioFileStorage;
import chat4all.grpc.generated.v1.*;
import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.grpc.stub.StreamObserver;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * RestGateway - HTTP REST API Gateway para o Chat4All
 * 
 * ENDPOINTS:
 * - GET  /health - Health check
 * - POST /users - Register new user
 * - GET  /users - List all users
 * - POST /auth - Login
 * - GET  /messages?conversationId=X - Get messages
 * - POST /messages - Send message
 */
public class RestGateway {
    
    private final HttpServer server;
    private final Gson gson;
    private final AuthServiceImpl authService;
    private final MessageServiceImpl messageService;
    private final CassandraMessageRepository messageRepository;
    private final MinioFileStorage fileStorage;
    private final chat4all.api.kafka.MessageProducer messageProducer;
    
    // Metadados de arquivos (fileId -> FileMetadata)
    private final Map<String, FileMetadata> fileMetadataStore = new HashMap<>();
    
    static class FileMetadata {
        String fileId;
        String fileName;
        String contentType;
        long size;
        String storagePath; // Path no MinIO
        
        FileMetadata(String fileId, String fileName, String contentType, long size, String storagePath) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.contentType = contentType;
            this.size = size;
            this.storagePath = storagePath;
        }
    }
    
    public RestGateway(int port, AuthServiceImpl authService, MessageServiceImpl messageService, CassandraMessageRepository messageRepository, MinioFileStorage fileStorage, chat4all.api.kafka.MessageProducer messageProducer) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.gson = new Gson();
        this.authService = authService;
        this.messageService = messageService;
        this.messageRepository = messageRepository;
        this.fileStorage = fileStorage;
        this.messageProducer = messageProducer;
        
        // Registrar handlers
        server.createContext("/health", new HealthHandler());
        server.createContext("/users", new UsersHandler());
        server.createContext("/auth", new AuthHandler());
        server.createContext("/messages", new MessagesHandler());
        server.createContext("/groups", new GroupsHandler());
        server.createContext("/files/upload", new FileUploadHandler());
        server.createContext("/files/", new FileDownloadHandler());
        
        server.setExecutor(null);
        
        System.out.println("✅ HTTP REST Gateway configurado na porta " + port);
    }
    
    public void start() {
        server.start();
        System.out.println("✓ HTTP REST Gateway started on port " + server.getAddress().getPort());
    }
    
    public void stop() {
        server.stop(0);
    }
    
    private void addCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.remove("Access-Control-Allow-Origin");
        headers.remove("Access-Control-Allow-Methods");
        headers.remove("Access-Control-Allow-Headers");
        headers.remove("Access-Control-Max-Age");
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        headers.add("Access-Control-Max-Age", "3600");
    }
    
    private boolean handleCorsPreFlight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }
    
    private void sendResponse(HttpExchange exchange, int status, Object data) throws IOException {
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes("UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        sendResponse(exchange, status, error);
    }
    
    private String getAuthToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }
    
    /**
     * /health - Health check
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreFlight(exchange)) return;
            Map<String, String> response = new HashMap<>();
            response.put("status", "up");
            sendResponse(exchange, 200, response);
        }
    }
    
    /**
     * /users - Register (POST) / List users (GET)
     */
    private class UsersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("[UsersHandler] " + exchange.getRequestMethod() + " /users - RemoteAddress: " + exchange.getRemoteAddress());
            
            if (handleCorsPreFlight(exchange)) {
                System.out.println("[UsersHandler] CORS preflight handled");
                return;
            }
            
            if ("POST".equals(exchange.getRequestMethod())) {
                handleRegister(exchange);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                handleListUsers(exchange);
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        }
        
        private void handleRegister(HttpExchange exchange) throws IOException {
            System.out.println("[UsersHandler] Starting registration...");
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), "UTF-8");
                System.out.println("[UsersHandler] Request body: " + body);
                @SuppressWarnings("unchecked")
                Map<String, String> request = gson.fromJson(body, Map.class);
                
                String username = request.get("username");
                String password = request.get("password");
                
                System.out.println("[UsersHandler] Username: " + username);
                
                if (username == null || password == null) {
                    sendError(exchange, 400, "Username and password required");
                    return;
                }
                
                RegisterRequest grpcRequest = RegisterRequest.newBuilder()
                    .setUsername(username)
                    .setPassword(password)
                    .setEmail(username + "@chat4all.local")
                    .build();
                
                System.out.println("[UsersHandler] Calling authService.register()...");
                CompletableFuture<RegisterResponse> future = new CompletableFuture<>();
                authService.register(grpcRequest, new StreamObserver<RegisterResponse>() {
                    @Override
                    public void onNext(RegisterResponse value) { 
                        System.out.println("[UsersHandler] Got response from authService");
                        future.complete(value); 
                    }
                    @Override
                    public void onError(Throwable t) { 
                        System.err.println("[UsersHandler] Error from authService: " + t.getMessage());
                        future.completeExceptionally(t); 
                    }
                    @Override
                    public void onCompleted() {
                        System.out.println("[UsersHandler] authService.register() completed");
                    }
                });
                
                System.out.println("[UsersHandler] Waiting for authService response...");
                RegisterResponse grpcResponse = future.get();
                System.out.println("[UsersHandler] Got response, sending to client...");
                
                Map<String, Object> response = new HashMap<>();
                response.put("userId", grpcResponse.getUserId());
                response.put("username", grpcResponse.getUsername());
                sendResponse(exchange, 201, response);
                
                System.out.println("[UsersHandler] Response sent successfully");
                
            } catch (Exception e) {
                System.err.println("[UsersHandler] Exception: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, e.getMessage());
            }
        }
        
        private void handleListUsers(HttpExchange exchange) throws IOException {
            try {
                // Listar todos os usuários do Cassandra
                List<Map<String, Object>> users = messageRepository.getAllUsers();
                sendResponse(exchange, 200, users);
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error listing users");
            }
        }
    }
    
    /**
     * /auth - Login
     */
    private class AuthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreFlight(exchange)) return;
            
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method not allowed");
                    return;
                }
                
                String body = new String(exchange.getRequestBody().readAllBytes(), "UTF-8");
                @SuppressWarnings("unchecked")
                Map<String, String> request = gson.fromJson(body, Map.class);
                
                String username = request.get("username");
                String password = request.get("password");
                
                if (username == null || password == null) {
                    sendError(exchange, 400, "Username and password required");
                    return;
                }
                
                LoginRequest grpcRequest = LoginRequest.newBuilder()
                    .setUsername(username)
                    .setPassword(password)
                    .build();
                
                CompletableFuture<LoginResponse> future = new CompletableFuture<>();
                authService.login(grpcRequest, new StreamObserver<LoginResponse>() {
                    @Override
                    public void onNext(LoginResponse value) { future.complete(value); }
                    @Override
                    public void onError(Throwable t) { future.completeExceptionally(t); }
                    @Override
                    public void onCompleted() {}
                });
                
                LoginResponse grpcResponse = future.get();
                Map<String, Object> response = new HashMap<>();
                response.put("token", grpcResponse.getAccessToken());
                response.put("userId", grpcResponse.getUserId());
                response.put("username", username);
                response.put("tokenType", "Bearer");
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 401, "Invalid credentials");
            }
        }
    }
    
    /**
     * /messages - Get messages (GET) / Send message (POST)
     */
    private class MessagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreFlight(exchange)) return;
            
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGetMessages(exchange);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                handleSendMessage(exchange);
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        }
        
        private void handleGetMessages(HttpExchange exchange) throws IOException {
            try {
                String query = exchange.getRequestURI().getQuery();
                if (query == null) {
                    sendError(exchange, 400, "Missing conversationId parameter");
                    return;
                }
                
                String conversationId = null;
                for (String param : query.split("&")) {
                    String[] parts = param.split("=");
                    if (parts.length == 2 && "conversationId".equals(parts[0])) {
                        conversationId = parts[1];
                    }
                }
                
                if (conversationId == null) {
                    sendError(exchange, 400, "Missing conversationId parameter");
                    return;
                }
                
                List<Map<String, Object>> messages = messageRepository.getMessages(conversationId, 100, 0);
                sendResponse(exchange, 200, messages);
                
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error getting messages");
            }
        }
        
        private void handleSendMessage(HttpExchange exchange) throws IOException {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), "UTF-8");
                System.out.println("[SendMessage] Request body: " + body);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> request = gson.fromJson(body, Map.class);
                
                String conversationId = (String) request.get("conversationId");
                String content = (String) request.get("content");
                String senderId = (String) request.get("senderId");
                String fileId = (String) request.get("fileId");
                String fileName = (String) request.get("fileName");
                Object fileSizeObj = request.get("fileSize");
                
                System.out.println("[SendMessage] Parsed - conversationId: " + conversationId + 
                                   ", senderId: " + senderId + ", content: " + content +
                                   ", fileId: " + fileId + ", fileName: " + fileName + 
                                   ", fileSize: " + fileSizeObj);
                
                if (conversationId == null || content == null || senderId == null) {
                    sendError(exchange, 400, "Missing required fields (conversationId, content, senderId)");
                    return;
                }
                
                // Gerar ID e timestamp para a mensagem
                String messageId = "msg_" + java.util.UUID.randomUUID().toString();
                long timestamp = System.currentTimeMillis();
                
                try {
                    // Preparar file metadata se houver arquivo
                    Map<String, String> fileMetadata = null;
                    String fileMetadataJson = "null";
                    if (fileId != null && !fileId.isEmpty()) {
                        System.out.println("[SendMessage] Mensagem com arquivo: " + fileId);
                        fileMetadata = new HashMap<>();
                        fileMetadata.put("file_name", fileName != null ? fileName : "arquivo");
                        if (fileSizeObj != null) {
                            // Converter para long (pode vir como Double do JSON)
                            long fileSize;
                            if (fileSizeObj instanceof Double) {
                                fileSize = ((Double) fileSizeObj).longValue();
                            } else if (fileSizeObj instanceof Integer) {
                                fileSize = ((Integer) fileSizeObj).longValue();
                            } else {
                                fileSize = Long.parseLong(String.valueOf(fileSizeObj));
                            }
                            fileMetadata.put("file_size", String.valueOf(fileSize));
                            System.out.println("[SendMessage] File size converted: " + fileSize);
                        }
                        System.out.println("[SendMessage] File metadata: " + fileMetadata);
                        fileMetadataJson = gson.toJson(fileMetadata);
                    }
                    
                    // ALTERAÇÃO: Apenas publicar no Kafka, Router Worker salvará no Cassandra
                    // Isso evita duplicação e permite que Router Worker faça notificações
                    String fileIdSafe = (fileId != null && !fileId.isEmpty()) ? fileId : "";
                    String messageJson = String.format(
                        "{\"message_id\":\"%s\",\"conversation_id\":\"%s\",\"sender_id\":\"%s\",\"content\":\"%s\",\"timestamp\":%d,\"file_id\":\"%s\",\"file_metadata\":%s,\"status\":\"SENT\",\"event_type\":\"MESSAGE_SENT\"}",
                        messageId, conversationId, senderId, content.replace("\"", "\\\""), timestamp, fileIdSafe, fileMetadataJson
                    );
                    messageProducer.publish(conversationId, messageJson);
                    System.out.println("[SendMessage] 📤 Mensagem publicada no Kafka (Router Worker salvará no Cassandra e notificará membros)");
                    
                    Map<String, Object> response = new HashMap<>();
                    response.put("messageId", messageId);
                    response.put("status", "SENT");
                    response.put("timestamp", timestamp);
                    sendResponse(exchange, 201, response);
                } catch (Exception dbError) {
                    System.err.println("[SendMessage] Database error: " + dbError.getMessage());
                    dbError.printStackTrace();
                    sendError(exchange, 500, "Error saving message");
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error sending message");
            }
        }
    }
    
    // =============================================================================
    // GROUPS HANDLER
    // =============================================================================
    
    class GroupsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("[GroupsHandler] " + exchange.getRequestMethod() + " /groups");
            
            if (handleCorsPreFlight(exchange)) {
                return;
            }
            
            if ("POST".equals(exchange.getRequestMethod())) {
                handleCreateGroup(exchange);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                handleListGroups(exchange);
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        }
        
        private void handleCreateGroup(HttpExchange exchange) throws IOException {
            try {
                String token = getAuthToken(exchange);
                if (token == null) {
                    sendError(exchange, 401, "Authorization required");
                    return;
                }
                
                // Extrair userId do token JWT (decode payload)
                String creatorId = null;
                try {
                    String[] parts = token.split("\\.");
                    if (parts.length == 3) {
                        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), "UTF-8");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> claims = gson.fromJson(payload, Map.class);
                        creatorId = (String) claims.get("sub");
                    }
                } catch (Exception e) {
                    System.err.println("Erro ao decodificar token: " + e.getMessage());
                }
                
                if (creatorId == null) {
                    sendError(exchange, 401, "Invalid token");
                    return;
                }
                
                String body = new String(exchange.getRequestBody().readAllBytes(), "UTF-8");
                System.out.println("[GroupsHandler] Request body: " + body);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> request = gson.fromJson(body, Map.class);
                
                String groupName = (String) request.get("groupName");
                @SuppressWarnings("unchecked")
                List<String> memberIds = (List<String>) request.get("memberIds");
                
                if (groupName == null || groupName.trim().isEmpty()) {
                    sendError(exchange, 400, "Group name is required");
                    return;
                }
                
                if (memberIds == null || memberIds.isEmpty()) {
                    sendError(exchange, 400, "At least one member is required");
                    return;
                }
                
                // CRITICAL FIX: Adicionar o criador à lista de participantes
                List<String> allParticipants = new ArrayList<>(memberIds);
                if (!allParticipants.contains(creatorId)) {
                    allParticipants.add(creatorId);
                    System.out.println("✓ Criador adicionado à lista de participantes: " + creatorId);
                }
                
                // Gerar ID do grupo
                String groupId = "group_" + UUID.randomUUID().toString();
                long timestamp = System.currentTimeMillis();
                
                // Criar grupo no Cassandra
                System.out.println("📝 Creating group: " + groupName);
                System.out.println("   Participants (including creator): " + allParticipants.size());
                
                messageRepository.createGroup(groupId, groupName, allParticipants, "GROUP");
                
                // Criar resposta
                Map<String, Object> response = new HashMap<>();
                response.put("group_id", groupId);
                response.put("name", groupName);
                response.put("member_ids", allParticipants);
                response.put("type", "GROUP");
                response.put("created_at", timestamp);
                
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error creating group: " + e.getMessage());
            }
        }
        
        private void handleListGroups(HttpExchange exchange) throws IOException {
            try {
                // Validar token
                String token = getAuthToken(exchange);
                if (token == null) {
                    sendError(exchange, 401, "Authorization required");
                    return;
                }
                
                // Pegar userId da query string
                String query = exchange.getRequestURI().getQuery();
                String userId = null;
                
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] parts = param.split("=");
                        if (parts.length == 2 && "userId".equals(parts[0])) {
                            userId = parts[1];
                        }
                    }
                }
                
                if (userId == null) {
                    sendError(exchange, 400, "Missing userId parameter");
                    return;
                }
                
                System.out.println("[ListGroups] Buscando grupos para usuário: " + userId);
                
                // Buscar grupos do usuário no Cassandra
                List<CassandraMessageRepository.Group> groups = messageRepository.getUserGroups(userId);
                
                // Converter para formato JSON
                List<Map<String, Object>> groupsList = new ArrayList<>();
                for (CassandraMessageRepository.Group group : groups) {
                    Map<String, Object> groupMap = new HashMap<>();
                    groupMap.put("group_id", group.getGroupId());
                    groupMap.put("name", group.getName());
                    groupMap.put("member_ids", group.getParticipantIds());
                    groupMap.put("type", group.getType());
                    groupMap.put("created_at", group.getCreatedAt());
                    groupsList.add(groupMap);
                }
                
                System.out.println("[ListGroups] Encontrados " + groupsList.size() + " grupos");
                
                Map<String, Object> response = new HashMap<>();
                response.put("groups", groupsList);
                
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error listing groups: " + e.getMessage());
            }
        }
    }
    
    // =============================================================================
    // FILE UPLOAD HANDLER
    // =============================================================================
    
    class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if (handleCorsPreFlight(exchange)) return;
            
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    // Ler o corpo da requisição
                    InputStream is = exchange.getRequestBody();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] data = new byte[16384];
                    int nRead;
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    byte[] fileContent = buffer.toByteArray();
                    
                    // Extrair nome do arquivo do header Content-Disposition (se houver)
                    String contentDisposition = exchange.getRequestHeaders().getFirst("Content-Disposition");
                    String fileName = "arquivo";
                    if (contentDisposition != null && contentDisposition.contains("filename=")) {
                        fileName = contentDisposition.split("filename=")[1].replaceAll("\"", "").split(";")[0];
                    }
                    
                    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (contentType == null) contentType = "application/octet-stream";
                    
                    // Usar conversationId "temp" se não fornecido (pode ser extraído do recipientId depois)
                    String conversationId = "uploads";
                    
                    // Upload para MinIO
                    MinioFileStorage.UploadResult uploadResult = fileStorage.uploadFile(
                        fileName, 
                        fileContent, 
                        contentType, 
                        conversationId
                    );
                    
                    // Armazenar metadados
                    FileMetadata metadata = new FileMetadata(
                        uploadResult.getFileId(),
                        uploadResult.getFilename(),
                        contentType,
                        uploadResult.getSizeBytes(),
                        uploadResult.getStoragePath()
                    );
                    fileMetadataStore.put(uploadResult.getFileId(), metadata);
                    
                    System.out.println("[FileUpload] Arquivo salvo no MinIO: " + uploadResult.getFileId() + 
                        " - " + fileName + " (" + fileContent.length + " bytes) - Path: " + uploadResult.getStoragePath());
                    
                    // Retornar resposta
                    Map<String, Object> response = new HashMap<>();
                    response.put("fileId", uploadResult.getFileId());
                    response.put("fileName", fileName);
                    response.put("fileSize", fileContent.length);
                    response.put("status", "uploaded");
                    response.put("storagePath", uploadResult.getStoragePath());
                    
                    sendResponse(exchange, 200, response);
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error processing file upload: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        }
    }
    
    // =============================================================================
    // FILE DOWNLOAD HANDLER
    // =============================================================================
    
    class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if (handleCorsPreFlight(exchange)) return;
            
            if ("GET".equals(exchange.getRequestMethod())) {
                try {
                    // Extrair fileId da URL (/files/{fileId})
                    String path = exchange.getRequestURI().getPath();
                    String fileId = path.substring(path.lastIndexOf('/') + 1);
                    
                    FileMetadata metadata = fileMetadataStore.get(fileId);
                    
                    if (metadata == null) {
                        sendError(exchange, 404, "File not found");
                        return;
                    }
                    
                    // Download do MinIO
                    InputStream fileStream = fileStorage.downloadFileByPath(metadata.storagePath);
                    
                    // Definir headers para download
                    Headers headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", metadata.contentType);
                    headers.set("Content-Disposition", "attachment; filename=\"" + metadata.fileName + "\"");
                    
                    // Enviar arquivo (streaming)
                    exchange.sendResponseHeaders(200, 0); // 0 = chunked transfer
                    OutputStream os = exchange.getResponseBody();
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fileStream.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                    
                    fileStream.close();
                    os.close();
                    
                    System.out.println("[FileDownload] Arquivo enviado do MinIO: " + fileId + " - " + metadata.fileName);
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error downloading file: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        }
    }
}
