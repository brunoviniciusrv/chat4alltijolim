package chat4all.api.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;

import java.net.InetSocketAddress;

/**
 * CassandraConnection - Gerenciamento de conexão com Cassandra (API Service)
 * 
 * PROPÓSITO EDUCACIONAL: Read-Only Access Pattern
 * ==================
 * 
 * NOTA ARQUITETURAL:
 * - Esta classe é uma DUPLICATA da mesma classe no router-worker
 * - Em produção real: usar módulo compartilhado
 * - Para Fase 1 educacional: duplicar para simplicidade
 * 
 * POR QUE DUPLICAR?
 * - Evita complexidade de módulo compartilhado
 * - API Service só precisa de READ queries
 * - Worker precisa de WRITE queries
 * - Mantém microservices independentes
 * 
 * TRADE-OFF EDUCACIONAL:
 * - Vantagem: Simplicidade, fácil de entender
 * - Desvantagem: Código duplicado (DRY violation)
 * - Decisão: OK para projeto didático Fase 1
 * 
 * @author Chat4All Educational Project
 */
public class CassandraConnection {
    
    private final CqlSession session;
    
    public CassandraConnection() {
        String contactPoints = System.getenv().getOrDefault("CASSANDRA_CONTACT_POINTS", "cassandra");
        int port = Integer.parseInt(System.getenv().getOrDefault("CASSANDRA_PORT", "9042"));
        String keyspace = System.getenv().getOrDefault("CASSANDRA_KEYSPACE", "chat4all");
        String datacenter = System.getenv().getOrDefault("CASSANDRA_DATACENTER", "dc1");
        
        System.out.println("API Service connecting to Cassandra:");
        System.out.println("  Contact Points: " + contactPoints);
        System.out.println("  Keyspace: " + keyspace);
        
        CqlSessionBuilder builder = CqlSession.builder();
        
        for (String host : contactPoints.split(",")) {
            builder.addContactPoint(new InetSocketAddress(host.trim(), port));
        }
        
        builder.withLocalDatacenter(datacenter);
        builder.withKeyspace(keyspace);
        
        this.session = builder.build();
        
        System.out.println("✓ API Service connected to Cassandra");
    }
    
    public CqlSession getSession() {
        return session;
    }
    
    public void close() {
        if (session != null && !session.isClosed()) {
            session.close();
            System.out.println("✓ API Service Cassandra connection closed");
        }
    }
}
