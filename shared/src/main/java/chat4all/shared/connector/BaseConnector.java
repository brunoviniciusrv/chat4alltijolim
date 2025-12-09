package chat4all.shared.connector;

import chat4all.shared.MessageEvent;
import chat4all.shared.patterns.CircuitBreakerPattern;
import chat4all.shared.patterns.GenericCircuitBreaker;

/**
 * Classe abstrata base para todos os conectores de plataforma
 * 
 * PROPÓSITO: Centralizar lógica comum de conectores (circuit breaker, health check, logging)
 * evitando duplicação entre WhatsApp, Instagram, Telegram, etc
 * 
 * RESPONSABILIDADES:
 * - Circuit Breaker para resilience
 * - Health check (ping periódico)
 * - Métricas de conector
 * - Logging estruturado
 * - Lifecycle (initialize, shutdown)
 * 
 * EXEMPLO DE IMPLEMENTAÇÃO:
 * ```java
 * public class WhatsAppConnector extends BaseConnector {
 *     
 *     @Override
 *     public String getConnectorId() {
 *         return "whatsapp";
 *     }
 *     
 *     @Override
 *     protected boolean sendMessageImpl(MessageEvent message) {
 *         // Implementação específica do WhatsApp
 *         String phoneNumber = extractPhoneNumber(message.getConversationId());
 *         return sendViaWhatsAppAPI(phoneNumber, message.getContent());
 *     }
 * }
 * ```
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public abstract class BaseConnector implements PlatformConnector {
    
    protected final CircuitBreakerPattern circuitBreaker;
    protected volatile boolean healthy;
    
    /**
     * Construtor base para conectores
     * 
     * Inicializa circuit breaker e health status
     */
    protected BaseConnector() {
        String connectorId = getConnectorId();
        this.circuitBreaker = new GenericCircuitBreaker("connector-" + connectorId);
        this.healthy = true;
        
        System.out.println("✅ Base Connector initialized for: " + connectorId);
    }
    
    /**
     * Envia mensagem com suporte a circuit breaker e retry
     * 
     * FLUXO:
     * 1. Valida circuit breaker (se OPEN, falha rápido)
     * 2. Tenta enviar mensagem
     * 3. Se sucesso: registra no circuit breaker
     * 4. Se falha: registra e pode abrir circuit
     * 
     * @param message Mensagem a enviar
     * @return true se enviado, false se falha
     * @throws ConnectorException se erro
     */
    @Override
    public final boolean sendMessage(MessageEvent message) throws ConnectorException {
        // 1. Circuit breaker check
        if (!circuitBreaker.allowRequest()) {
            healthy = false;
            throw new ConnectorException(
                getConnectorId(),
                "CIRCUIT_OPEN",
                "Circuit breaker is OPEN, rejecting request"
            );
        }
        
        try {
            // 2. Implementação específica
            boolean sent = sendMessageImpl(message);
            
            if (sent) {
                // 3. Registra sucesso
                circuitBreaker.recordSuccess();
                healthy = true;
                System.out.println("✅ Message sent via " + getConnectorId() + ": " + message.getMessageId());
            } else {
                // Falha sem exception
                circuitBreaker.recordFailure();
                healthy = false;
                System.out.println("❌ Failed to send message via " + getConnectorId());
            }
            
            return sent;
            
        } catch (Exception e) {
            // 4. Registra falha no circuit breaker
            circuitBreaker.recordFailure();
            healthy = false;
            
            throw new ConnectorException(
                getConnectorId(),
                "SEND_ERROR",
                "Failed to send message: " + e.getMessage(),
                e
            );
        }
    }
    
    /**
     * Implementação específica de envio
     * Deve ser sobrescrita por cada conector
     * 
     * @param message Mensagem a enviar
     * @return true se enviado com sucesso
     * @throws Exception se erro ao enviar
     */
    protected abstract boolean sendMessageImpl(MessageEvent message) throws Exception;
    
    @Override
    public boolean isHealthy() {
        return healthy && circuitBreaker.getState() != CircuitBreakerPattern.State.OPEN;
    }
    
    @Override
    public void initialize() throws ConnectorException {
        System.out.println("Initializing connector: " + getConnectorId());
    }
    
    @Override
    public void shutdown() {
        System.out.println("Shutting down connector: " + getConnectorId());
        healthy = false;
    }
    
    /**
     * Retorna circuit breaker deste conector
     * (util para testes e métricas)
     * 
     * @return circuit breaker
     */
    protected CircuitBreakerPattern getCircuitBreaker() {
        return circuitBreaker;
    }
}
