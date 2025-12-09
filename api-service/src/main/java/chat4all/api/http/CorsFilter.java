package chat4all.api.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public class CorsFilter extends Filter {
    
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        System.out.println("🔍 CORS Filter called for: " + exchange.getRequestURI());
        
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        
        chain.doFilter(exchange);
    }
    
    @Override
    public String description() {
        return "CORS Filter";
    }
}
