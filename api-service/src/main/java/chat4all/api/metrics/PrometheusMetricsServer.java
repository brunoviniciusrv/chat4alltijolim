package chat4all.api.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PrometheusMetricsServer - HTTP server para expor métricas Prometheus
 * 
 * IMPLEMENTA:
 * - RNF-002: Métricas para SLA monitoring
 * - RNF-006: Latência P95/P99
 * - RNF-001: Throughput e escalabilidade
 * 
 * MÉTRICAS EXPOSTAS:
 * - grpc_requests_total: Total de requisições
 * - grpc_requests_failed_total: Requisições falhadas
 * - grpc_request_duration_seconds: Histograma de latência
 * - up: Health check (1=up, 0=down)
 * - messages_sent_total: Total de mensagens enviadas
 * - messages_delivered_total: Total de mensagens entregues
 * 
 * ENDPOINT: http://localhost:8080/metrics
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class PrometheusMetricsServer {
    
    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsServer.class);
    
    private final PrometheusMeterRegistry registry;
    private final HttpServer server;
    private final int port;
    
    // Contadores
    private final Counter requestsTotal;
    private final Counter requestsFailed;
    private final Counter messagesSent;
    private final Counter messagesDelivered;
    
    // Timers (para histogramas de latência)
    private final ConcurrentHashMap<String, Timer> timers;
    
    // Health status
    private final AtomicInteger upStatus;
    
    /**
     * Construtor
     * 
     * @param port Porta HTTP para expor métricas (padrão: 8080)
     */
    public PrometheusMetricsServer(int port) throws IOException {
        this.port = port;
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.timers = new ConcurrentHashMap<>();
        this.upStatus = new AtomicInteger(1); // 1 = up
        
        // Criar contadores
        this.requestsTotal = Counter.builder("grpc_requests_total")
            .description("Total gRPC requests")
            .register(registry);
        
        this.requestsFailed = Counter.builder("grpc_requests_failed_total")
            .description("Total failed gRPC requests")
            .register(registry);
        
        this.messagesSent = Counter.builder("messages_sent_total")
            .description("Total messages sent")
            .register(registry);
        
        this.messagesDelivered = Counter.builder("messages_delivered_total")
            .description("Total messages delivered")
            .register(registry);
        
        // Gauge para health status
        registry.gauge("up", upStatus);
        
        // Criar HTTP server
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/metrics", exchange -> {
            try {
                String response = registry.scrape();
                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } catch (Exception e) {
                log.error("Error serving metrics", e);
                String error = "Error: " + e.getMessage();
                exchange.sendResponseHeaders(500, error.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes());
                }
            }
        });
        
        // Health check endpoint
        this.server.createContext("/health", exchange -> {
            String response = "{\"status\":\"up\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        
        log.info("Prometheus metrics server configured on port {}", port);
    }
    
    /**
     * Inicia o servidor HTTP
     */
    public void start() {
        server.start();
        log.info("✓ Prometheus metrics endpoint: http://localhost:{}/metrics", port);
        log.info("✓ Health check endpoint: http://localhost:{}/health", port);
    }
    
    /**
     * Para o servidor HTTP
     */
    public void stop() {
        if (server != null) {
            log.info("Stopping Prometheus metrics server...");
            server.stop(0);
        }
    }
    
    /**
     * Incrementa contador de requisições totais
     */
    public void incrementRequests() {
        requestsTotal.increment();
    }
    
    /**
     * Incrementa contador de requisições falhadas
     */
    public void incrementFailedRequests() {
        requestsFailed.increment();
    }
    
    /**
     * Incrementa contador de mensagens enviadas
     */
    public void incrementMessagesSent() {
        messagesSent.increment();
    }
    
    /**
     * Incrementa contador de mensagens entregues
     */
    public void incrementMessagesDelivered() {
        messagesDelivered.increment();
    }
    
    /**
     * Registra duração de uma operação
     * 
     * @param operation Nome da operação (ex: "SendMessage", "UploadFile")
     * @param durationMs Duração em milissegundos
     */
    public void recordDuration(String operation, long durationMs) {
        Timer timer = timers.computeIfAbsent(operation, op -> 
            Timer.builder("grpc_request_duration_seconds")
                .description("gRPC request duration")
                .tag("operation", op)
                .register(registry)
        );
        timer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * Define status de saúde do serviço
     * 
     * @param isUp true se serviço está up, false se down
     */
    public void setUpStatus(boolean isUp) {
        upStatus.set(isUp ? 1 : 0);
    }
    
    /**
     * Obtém o registry do Micrometer
     */
    public MeterRegistry getRegistry() {
        return registry;
    }
}
