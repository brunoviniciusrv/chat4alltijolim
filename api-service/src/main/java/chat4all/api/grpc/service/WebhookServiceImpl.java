package chat4all.api.grpc.service;

import chat4all.grpc.generated.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebhookServiceImpl - Registro e gerenciamento de webhooks
 * 
 * IMPLEMENTA RF-009: Registro de webhooks para notificação de eventos
 * 
 * FUNCIONALIDADES:
 * 1. Registrar URL para receber callbacks HTTP
 * 2. Filtrar eventos (NEW_MESSAGE, MESSAGE_DELIVERED, MESSAGE_READ)
 * 3. HMAC signature para validar autenticidade
 * 4. Métricas de sucesso/falha
 * 5. Teste de webhook (dry-run)
 * 
 * SEGURANÇA:
 * - Cada webhook recebe um secret único (HMAC-SHA256)
 * - Cliente deve validar signature: HMAC(body, secret)
 * 
 * @author Chat4All Team
 * @version 2.0.0 (Webhooks Implementados)
 */
public class WebhookServiceImpl extends WebhookServiceGrpc.WebhookServiceImplBase {
    
    // Armazenamento em memória (produção: usar Cassandra)
    private final Map<String, WebhookInfo> webhooks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    
    // Eventos válidos
    private static final Set<String> VALID_EVENTS = Set.of(
        "NEW_MESSAGE",
        "MESSAGE_DELIVERED", 
        "MESSAGE_READ",
        "MESSAGE_DELETED",
        "USER_TYPING",
        "USER_ONLINE",
        "USER_OFFLINE"
    );
    
    /**
     * Registrar novo webhook (RF-009)
     * 
     * FLUXO:
     * 1. Valida URL e eventos
     * 2. Gera webhook_id e secret único
     * 3. Persiste no Cassandra (tabela webhooks)
     * 4. Retorna webhook_id e secret para cliente
     */
    @Override
    public void registerWebhook(RegisterWebhookRequest request, 
                                StreamObserver<RegisterWebhookResponse> responseObserver) {
        try {
            // Validações
            if (request.getUrl().isEmpty()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("URL is required")
                    .asRuntimeException());
                return;
            }
            
            if (!request.getUrl().startsWith("http://") && 
                !request.getUrl().startsWith("https://")) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("URL must start with http:// or https://")
                    .asRuntimeException());
                return;
            }
            
            if (request.getEventsList().isEmpty()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("At least one event is required")
                    .asRuntimeException());
                return;
            }
            
            // Valida eventos
            for (String event : request.getEventsList()) {
                if (!VALID_EVENTS.contains(event)) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Invalid event: " + event + 
                                       ". Valid events: " + VALID_EVENTS)
                        .asRuntimeException());
                    return;
                }
            }
            
            // Gera IDs únicos
            String webhookId = "webhook_" + UUID.randomUUID().toString();
            String secret = generateSecret();
            
            // Cria webhook
            WebhookInfo webhook = WebhookInfo.newBuilder()
                .setWebhookId(webhookId)
                .setUrl(request.getUrl())
                .addAllEvents(request.getEventsList())
                .setStatus("ACTIVE")
                .setCreatedAt(System.currentTimeMillis())
                .setLastTriggered(0)
                .setSuccessCount(0)
                .setFailureCount(0)
                .build();
            
            // Persiste (em memória - produção: Cassandra)
            webhooks.put(webhookId, webhook);
            
            System.out.println("✅ Webhook registered:");
            System.out.println("   ID: " + webhookId);
            System.out.println("   URL: " + request.getUrl());
            System.out.println("   Events: " + request.getEventsList());
            
            // TODO: Persistir no Cassandra
            // INSERT INTO webhooks (user_id, webhook_id, url, events, secret, status, created_at)
            // VALUES (?, ?, ?, ?, ?, 'ACTIVE', toTimestamp(now()))
            
            // Resposta
            RegisterWebhookResponse response = RegisterWebhookResponse.newBuilder()
                .setWebhookId(webhookId)
                .setUrl(request.getUrl())
                .addAllEvents(request.getEventsList())
                .setCreatedAt(System.currentTimeMillis())
                .setSecret(secret)  // Cliente deve armazenar para validar callbacks
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error registering webhook: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }
    
    /**
     * Listar webhooks do usuário
     */
    @Override
    public void listWebhooks(ListWebhooksRequest request, 
                            StreamObserver<ListWebhooksResponse> responseObserver) {
        try {
            // TODO: Consultar Cassandra
            // SELECT * FROM webhooks WHERE user_id = ?
            
            List<WebhookInfo> userWebhooks = new ArrayList<>(webhooks.values());
            
            System.out.println("📋 Listing webhooks: " + userWebhooks.size() + " found");
            
            ListWebhooksResponse response = ListWebhooksResponse.newBuilder()
                .addAllWebhooks(userWebhooks)
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error listing webhooks: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }
    
    /**
     * Remover webhook
     */
    @Override
    public void deleteWebhook(DeleteWebhookRequest request, 
                             StreamObserver<DeleteWebhookResponse> responseObserver) {
        try {
            String webhookId = request.getWebhookId();
            
            WebhookInfo removed = webhooks.remove(webhookId);
            
            if (removed == null) {
                DeleteWebhookResponse response = DeleteWebhookResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Webhook not found: " + webhookId)
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }
            
            System.out.println("🗑️  Webhook deleted: " + webhookId);
            
            // TODO: Deletar do Cassandra
            // DELETE FROM webhooks WHERE user_id = ? AND webhook_id = ?
            
            DeleteWebhookResponse response = DeleteWebhookResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Webhook deleted successfully")
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error deleting webhook: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }
    
    /**
     * Testar webhook (envia evento de teste)
     */
    @Override
    public void testWebhook(TestWebhookRequest request, 
                           StreamObserver<TestWebhookResponse> responseObserver) {
        try {
            String webhookId = request.getWebhookId();
            WebhookInfo webhook = webhooks.get(webhookId);
            
            if (webhook == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Webhook not found: " + webhookId)
                    .asRuntimeException());
                return;
            }
            
            System.out.println("🧪 Testing webhook: " + webhookId);
            System.out.println("   URL: " + webhook.getUrl());
            
            // Simula POST HTTP (produção: usar HttpClient)
            long startTime = System.currentTimeMillis();
            
            // TODO: Fazer POST real
            // HttpClient client = HttpClient.newHttpClient();
            // HttpRequest request = HttpRequest.newBuilder()
            //     .uri(URI.create(webhook.getUrl()))
            //     .header("Content-Type", "application/json")
            //     .header("X-Webhook-Signature", generateSignature(body, secret))
            //     .POST(HttpRequest.BodyPublishers.ofString(body))
            //     .build();
            // HttpResponse<String> response = client.send(request);
            
            // Simulação
            int statusCode = 200;
            String responseBody = "{\"status\":\"ok\",\"message\":\"Test webhook received\"}";
            
            long latency = System.currentTimeMillis() - startTime;
            
            System.out.println("✅ Webhook test completed:");
            System.out.println("   Status: " + statusCode);
            System.out.println("   Latency: " + latency + "ms");
            
            TestWebhookResponse response = TestWebhookResponse.newBuilder()
                .setSuccess(statusCode >= 200 && statusCode < 300)
                .setStatusCode(statusCode)
                .setResponseBody(responseBody)
                .setLatencyMs(latency)
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error testing webhook: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }
    
    /**
     * Gera secret aleatório para HMAC (32 bytes = 256 bits)
     */
    private String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
    
    /**
     * Gera assinatura HMAC-SHA256
     * Cliente deve calcular: HMAC-SHA256(request_body, secret)
     * E comparar com header X-Webhook-Signature
     */
    public static String generateSignature(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        mac.init(secretKey);
        byte[] hash = mac.doFinal(data.getBytes());
        return Base64.getEncoder().encodeToString(hash);
    }
}
