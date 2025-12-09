package chat4all.shared.patterns;

/**
 * Interface para implementação do padrão Circuit Breaker
 * 
 * PROPÓSITO: Abstrair a implementação de circuit breaker para reutilização
 * entre múltiplos conectores (WhatsApp, Instagram, etc.)
 * 
 * PATTERN: Behavioral Pattern - Resilience
 * 
 * Estados:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Failure threshold reached, requests fail fast
 * - HALF_OPEN: Testing if service recovered
 * 
 * Uso:
 * ```java
 * CircuitBreakerPattern breaker = new GenericCircuitBreaker(metricsRegistry);
 * if (breaker.allowRequest()) {
 *     try {
 *         makeApiCall();
 *         breaker.recordSuccess();
 *     } catch (Exception e) {
 *         breaker.recordFailure();
 *     }
 * } else {
 *     return cachedResponse(); // fallback
 * }
 * ```
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public interface CircuitBreakerPattern {
    
    /**
     * Estados possíveis do circuit breaker
     */
    enum State {
        CLOSED,      // Normal operation
        OPEN,        // Failing fast (reject requests)
        HALF_OPEN    // Testing recovery (allow single request)
    }
    
    /**
     * Verifica se requisição deve ser permitida
     * 
     * @return true se requisição allowed, false if circuit is OPEN
     */
    boolean allowRequest();
    
    /**
     * Registra sucesso de uma operação
     * Transição: HALF_OPEN → CLOSED (se bem-sucedido)
     */
    void recordSuccess();
    
    /**
     * Registra falha de uma operação
     * Transição: CLOSED → OPEN (após threshold), ou HALF_OPEN → OPEN
     */
    void recordFailure();
    
    /**
     * Retorna estado atual do circuit breaker
     * 
     * @return estado atual
     */
    State getState();
    
    /**
     * Retorna contador de falhas consecutivas
     * 
     * @return número de falhas consecutivas
     */
    int getConsecutiveFailures();
}
