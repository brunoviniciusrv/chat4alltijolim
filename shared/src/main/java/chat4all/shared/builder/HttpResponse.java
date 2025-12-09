package chat4all.shared.builder;

import java.util.HashMap;
import java.util.Map;

/**
 * Resposta HTTP padronizada para toda a aplicação
 * 
 * PADRÃO: Value Object + Builder Pattern
 * 
 * PROPÓSITO: Padronizar todas as respostas HTTP em um formato consistente
 * 
 * EXEMPLO:
 * ```java
 * HttpResponse response = new HttpResponse.Builder()
 *     .status(202)
 *     .message("Message accepted")
 *     .data("message_id", "msg_123")
 *     .data("status", "ACCEPTED")
 *     .build();
 * 
 * // Saída:
 * {
 *   "status": 202,
 *   "message": "Message accepted",
 *   "data": {
 *     "message_id": "msg_123",
 *     "status": "ACCEPTED"
 *   },
 *   "timestamp": 1672531200000
 * }
 * ```
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class HttpResponse {
    
    private final int status;
    private final String message;
    private final Map<String, Object> data;
    private final long timestamp;
    private final String error;
    
    private HttpResponse(Builder builder) {
        this.status = builder.status;
        this.message = builder.message;
        this.data = new HashMap<>(builder.data);
        this.timestamp = builder.timestamp;
        this.error = builder.error;
    }
    
    public int getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public Map<String, Object> getData() {
        return new HashMap<>(data);
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public String getError() {
        return error;
    }
    
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
    
    public boolean isError() {
        return error != null;
    }
    
    /**
     * Builder padrão para construir respostas
     * 
     * BENEFÍCIOS DO BUILDER PATTERN:
     * - Fluent API: encadeamento de chamadas
     * - Validação: construtor privado garante estado válido
     * - Opcionais: pode definir ou não cada campo
     */
    public static class Builder {
        private int status = 200;
        private String message = "";
        private final Map<String, Object> data = new HashMap<>();
        private long timestamp = System.currentTimeMillis();
        private String error = null;
        
        public Builder status(int status) {
            this.status = status;
            return this;
        }
        
        public Builder message(String message) {
            this.message = message;
            return this;
        }
        
        public Builder data(String key, Object value) {
            this.data.put(key, value);
            return this;
        }
        
        public Builder data(Map<String, Object> dataMap) {
            this.data.putAll(dataMap);
            return this;
        }
        
        public Builder error(String error) {
            this.error = error;
            return this;
        }
        
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public HttpResponse build() {
            return new HttpResponse(this);
        }
    }
    
    @Override
    public String toString() {
        return "HttpResponse{" +
                "status=" + status +
                ", message='" + message + '\'' +
                ", data=" + data +
                ", timestamp=" + timestamp +
                ", error='" + error + '\'' +
                '}';
    }
}
