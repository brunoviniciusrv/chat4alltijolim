package chat4all.shared.patterns;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementação genérica do Circuit Breaker Pattern
 * 
 * PROPÓSITO: Centralizar lógica de circuit breaker em um único lugar
 * e reutilizar entre múltiplos conectores (WhatsApp, Instagram, etc.)
 * 
 * ANTES (DRY Violation):
 * - CircuitBreaker em connector-whatsapp/
 * - CircuitBreaker em connector-instagram/
 * → Mesmo código duplicado!
 * 
 * DEPOIS (DRY Principle):
 * - GenericCircuitBreaker em shared/
 * - Ambos os conectores injetam a mesma implementação
 * → Código centralizado, fácil manutenção
 * 
 * CONFIGURAÇÃO:
 * - FAILURE_THRESHOLD: 5 falhas consecutivas
 * - OPEN_TIMEOUT_MS: 30 segundos antes de tentar recuperação
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class GenericCircuitBreaker implements CircuitBreakerPattern {
    
    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_TIMEOUT_MS = 30_000; // 30 seconds
    
    private volatile State state;
    private final AtomicInteger consecutiveFailures;
    private final AtomicLong lastFailureTime;
    private final String name; // Para logging
    
    /**
     * Cria um novo circuit breaker
     * 
     * @param name Nome do circuit breaker (para logs e métricas)
     */
    public GenericCircuitBreaker(String name) {
        this.name = name;
        this.state = State.CLOSED;
        this.consecutiveFailures = new AtomicInteger(0);
        this.lastFailureTime = new AtomicLong(0);
        
        System.out.println("✅ Circuit Breaker '" + name + "' initialized (state=CLOSED)");
    }
    
    @Override
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        
        if (state == State.OPEN) {
            // Verifica se timeout expirou
            long now = System.currentTimeMillis();
            long timeSinceFailure = now - lastFailureTime.get();
            
            if (timeSinceFailure >= OPEN_TIMEOUT_MS) {
                // Transição para HALF_OPEN
                transitionTo(State.HALF_OPEN);
                System.out.println("⚠️  Circuit Breaker '" + name + "' transitioned to HALF_OPEN (testing recovery)");
                return true; // Permite test request
            }
            
            return false; // Ainda OPEN, rejeita request
        }
        
        // HALF_OPEN state: permite single test request
        return true;
    }
    
    @Override
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            transitionTo(State.CLOSED);
            System.out.println("✅ Circuit Breaker '" + name + "' recovered (HALF_OPEN → CLOSED)");
        }
        
        if (state == State.CLOSED) {
            consecutiveFailures.set(0);
        }
    }
    
    @Override
    public synchronized void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        
        if (state == State.HALF_OPEN) {
            // Test request falhou, volta a OPEN
            transitionTo(State.OPEN);
            System.out.println("❌ Circuit Breaker '" + name + "' test failed (HALF_OPEN → OPEN)");
            return;
        }
        
        if (state == State.CLOSED) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= FAILURE_THRESHOLD) {
                transitionTo(State.OPEN);
                System.out.println("❌ Circuit Breaker '" + name + "' OPEN after " + failures + " failures");
            }
        }
    }
    
    @Override
    public State getState() {
        return state;
    }
    
    @Override
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
    
    /**
     * Transição entre estados com logging
     * 
     * @param newState Novo estado
     */
    private void transitionTo(State newState) {
        State oldState = this.state;
        this.state = newState;
        System.out.println("🔄 Circuit Breaker '" + name + "': " + oldState + " → " + newState);
    }
    
    /**
     * Retorna string com status atual
     * 
     * @return string com informações do circuit breaker
     */
    @Override
    public String toString() {
        return "GenericCircuitBreaker{" +
                "name='" + name + '\'' +
                ", state=" + state +
                ", consecutiveFailures=" + consecutiveFailures.get() +
                ", lastFailureTime=" + lastFailureTime.get() +
                '}';
    }
}
