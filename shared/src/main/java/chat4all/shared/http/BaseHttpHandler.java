package chat4all.shared.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Classe base para todos os HTTP handlers
 * 
 * PROPÓSITO: Centralizar lógica comum (resposta, headers, erro)
 * evitando duplicação de código entre handlers
 * 
 * RESPONSABILIDADES:
 * - Enviar resposta JSON padronizada
 * - Configurar headers HTTP (Content-Type, etc)
 * - Tratamento de erros padronizado
 * - Log de requisições
 * 
 * EXEMPLO DE USO:
 * ```java
 * class AuthHandler extends BaseHttpHandler {
 *     @Override
 *     public String getPath() { return "/auth/token"; }
 *     
 *     @Override
 *     public String getMethod() { return "POST"; }
 *     
 *     @Override
 *     protected void handleRequest(HttpExchange exchange) throws Exception {
 *         String token = generateToken();
 *         sendJsonResponse(exchange, 200, Map.of(
 *             "token", token,
 *             "expires_in", 3600
 *         ));
 *     }
 * }
 * ```
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public abstract class BaseHttpHandler implements HttpHandlerContract {
    
    /**
     * Processa requisição HTTP
     * 
     * FLUXO:
     * 1. Valida método HTTP (GET, POST, etc)
     * 2. Chama implementação específica (handleRequest)
     * 3. Se erro: responde com erro padronizado
     * 
     * @param exchange HttpExchange
     */
    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        try {
            // Valida método
            if (!exchange.getRequestMethod().equals(getMethod())) {
                sendError(exchange, 405, "Method Not Allowed: " + exchange.getRequestMethod());
                return;
            }
            
            // Delega para implementação específica
            handleRequest(exchange);
            
        } catch (NumberFormatException e) {
            // Erro de validação de formato
            sendError(exchange, 400, "Bad Request: Invalid number format - " + e.getMessage());
            
        } catch (IllegalArgumentException e) {
            // Erro de validação
            sendError(exchange, 400, "Bad Request: " + e.getMessage());
            
        } catch (SecurityException e) {
            // Erro de autenticação/autorização
            sendError(exchange, 401, "Unauthorized: " + e.getMessage());
            
        } catch (Exception e) {
            // Erro genérico do servidor
            System.err.println("❌ Error in " + getPath() + ": " + e.getMessage());
            e.printStackTrace();
            sendError(exchange, 500, "Internal Server Error");
        } finally {
            exchange.close();
        }
    }
    
    /**
     * Implementação específica do handler
     * Subclasses devem sobrescrever este método
     * 
     * @param exchange HttpExchange
     * @throws Exception se erro ao processar
     */
    protected abstract void handleRequest(HttpExchange exchange) throws Exception;
    
    /**
     * Envia resposta JSON com status
     * 
     * @param exchange HttpExchange
     * @param status Status HTTP (200, 201, 202, etc)
     * @param data Dados para enviar como JSON
     */
    protected void sendJsonResponse(HttpExchange exchange, int status, Object data) throws IOException {
        String json = jsonEncode(data);
        
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.getBytes(StandardCharsets.UTF_8).length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }
    
    /**
     * Envia erro padronizado
     * 
     * @param exchange HttpExchange
     * @param status Status HTTP (400, 401, 500, etc)
     * @param message Mensagem de erro
     */
    protected void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("status", status);
        error.put("timestamp", System.currentTimeMillis());
        
        sendJsonResponse(exchange, status, error);
    }
    
    /**
     * Simples JSON encoding (produção deve usar biblioteca JSON)
     * 
     * @param obj Objeto a codificar
     * @return JSON string
     */
    /**
     * Serializar objeto para JSON string
     * Usa reflexão para serializar campos públicos
     * 
     * @param obj Objeto a serializar
     * @return JSON string
     */
    protected String serializeJson(Object obj) {
        if (obj instanceof String) {
            return (String) obj;
        }
        return jsonEncode(obj);
    }
    
    /**
     * Parse JSON string para objeto
     * Implementação simplista usando reflexão
     * 
     * @param json String JSON
     * @param clazz Classe target
     * @return Instância da classe preenchida com dados JSON
     */
    protected <T> T parseJson(String json, Class<T> clazz) {
        try {
            // Tentar usar reflexão para criar instância
            T instance = clazz.getDeclaredConstructor().newInstance();
            
            // Parse simples de JSON key:value
            String[] pairs = json.replaceAll("[{}\\s\"]", "").split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String key = kv[0];
                    String value = kv[1];
                    
                    // Tentar settar propriedade via reflection
                    try {
                        java.lang.reflect.Field field = clazz.getDeclaredField(key);
                        field.setAccessible(true);
                        
                        // Convert value para tipo certo
                        if (field.getType() == String.class) {
                            field.set(instance, value);
                        } else if (field.getType() == int.class) {
                            field.set(instance, Integer.parseInt(value));
                        } else if (field.getType() == long.class) {
                            field.set(instance, Long.parseLong(value));
                        } else if (field.getType() == boolean.class) {
                            field.set(instance, Boolean.parseBoolean(value));
                        }
                    } catch (NoSuchFieldException e) {
                        // Campo não existe, ignorar
                    }
                }
            }
            
            return instance;
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Ler corpo completo da requisição HTTP
     * 
     * @param exchange HttpExchange
     * @return Corpo como string
     * @throws IOException se erro ao ler
     */
    protected String readBody(HttpExchange exchange) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead = exchange.getRequestBody().read(buffer);
        return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
    }
    
    @SuppressWarnings("unchecked")
    private String jsonEncode(Object obj) {
        if (obj instanceof String) {
            return "\"" + obj + "\"";
        }
        if (obj instanceof Number) {
            return obj.toString();
        }
        if (obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":")
                  .append(jsonEncode(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return "null";
    }
}
