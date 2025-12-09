package chat4all.shared.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Interface padrão para todos os HTTP handlers da aplicação
 * 
 * PROPÓSITO: Definir contrato comum para todos os handlers
 * e facilitar criação de handlers seguindo os mesmos padrões
 * 
 * BENEFÍCIOS:
 * - Consistência: todos handlers seguem mesma estrutura
 * - Reutilização: lógica comum pode ser extraída para classe base
 * - Polimorfismo: handlers podem ser tratados uniformemente
 * 
 * EXEMPLOS DE HANDLERS:
 * - AuthHandler (POST /auth/token)
 * - MessagesHandler (POST /v1/messages)
 * - ConversationsHandler (GET /v1/conversations/{id}/messages)
 * - UserRegistrationHandler (POST /v1/users/register)
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public interface HttpHandlerContract {
    
    /**
     * Processa requisição HTTP
     * 
     * @param exchange HttpExchange com request/response
     * @throws IOException se erro ao processar
     */
    void handle(HttpExchange exchange) throws IOException;
    
    /**
     * Retorna caminho que este handler atende
     * 
     * @return path (ex: "/auth/token", "/v1/messages")
     */
    String getPath();
    
    /**
     * Retorna método HTTP que este handler atende
     * 
     * @return método HTTP (ex: "POST", "GET")
     */
    String getMethod();
}
