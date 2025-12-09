package chat4all.shared.metrics;

/**
 * Interface padrão para registro de métricas em componentes distribuídos
 * 
 * PROPÓSITO: Abstrair implementação de métricas (Prometheus, custom, etc)
 * e permitir uso consistente em todos os módulos
 * 
 * ANTES (Duplicação):
 * - MetricsRegistry em api-service/
 * - MetricsRegistry em router-worker/
 * - ConnectorMetricsRegistry em connector-whatsapp/
 * - ConnectorMetricsRegistry em connector-instagram/
 * → 4 implementações de métrica!
 * 
 * DEPOIS (Interface comum):
 * - MetricsRegistry em shared/ (interface)
 * - Implementações específicas por módulo
 * - Todos implementam contrato comum
 * 
 * MÉTRICAS PRINCIPAIS:
 * - request_count: Total de requisições
 * - request_duration_ms: Tempo de resposta
 * - error_count: Total de erros
 * - connector_state: Estado do circuit breaker
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
interface MetricsRegistry {
    
    /**
     * Incrementa contador de requisições
     * 
     * @param endpoint Endpoint da requisição (ex: "POST /v1/messages")
     * @param statusCode Status HTTP da resposta
     */
    void recordRequest(String endpoint, int statusCode);
    
    /**
     * Registra duração de uma requisição
     * 
     * @param endpoint Endpoint da requisição
     * @param durationMs Duração em milissegundos
     */
    void recordRequestDuration(String endpoint, long durationMs);
    
    /**
     * Incrementa contador de erros
     * 
     * @param endpoint Endpoint onde erro ocorreu
     * @param errorType Tipo de erro (ex: "ValidationException")
     */
    void recordError(String endpoint, String errorType);
    
    /**
     * Registra estado de um componente
     * 
     * @param componentName Nome do componente (ex: "circuit_breaker_whatsapp")
     * @param state Estado (ex: "OPEN", "CLOSED", "HALF_OPEN")
     */
    void recordComponentState(String componentName, String state);
    
    /**
     * Retorna todas as métricas coletadas
     * 
     * @return string formatada com métricas (Prometheus format ou JSON)
     */
    String getMetrics();
}
