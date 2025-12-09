package chat4all.worker.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;

import java.net.InetSocketAddress;

/**
 * CassandraConnection - Gerenciamento de conexão com Cassandra
 * 
 * PROPÓSITO EDUCACIONAL: Connection Pooling + Distributed Database Access
 * ==================
 * 
 * O QUE É UM DRIVER DE BANCO DE DADOS?
 * - Cliente que fala o protocolo do banco (CQL binary protocol para Cassandra)
 * - Mantém pool de conexões TCP reutilizáveis
 * - Gerencia retry logic, load balancing entre nós, failover
 * 
 * POR QUE NÃO USAR JDBC?
 * - JDBC é para bancos relacionais (SQL)
 * - Cassandra usa CQL (Cassandra Query Language) - similar mas não é SQL
 * - DataStax driver é otimizado para Cassandra distribuído
 * 
 * CONNECTION POOLING:
 * ```
 * Application → CqlSession → [Pool: 10 conexões TCP] → Cassandra Cluster
 *                              ↓
 *                    Reutiliza conexões existentes
 *                    (evita overhead de criar socket novo)
 * ```
 * 
 * CONFIGURAÇÃO:
 * - Contact Points: IPs dos nós Cassandra (datacenter-aware)
 * - Keyspace: Namespace lógico (como "database" no MySQL)
 * - Consistency Level: Quantos nós devem confirmar (configurável por query)
 * 
 * LIFECYCLE:
 * 1. Criar CqlSession (conecta aos contact points)
 * 2. Negocia protocolo CQL
 * 3. Descobre topologia do cluster (quais nós existem)
 * 4. Mantém pool de conexões
 * 5. Na aplicação: getSession() → reutiliza pool
 * 6. No shutdown: close() → fecha todas as conexões
 * 
 * @author Chat4All Educational Project
 */
public class CassandraConnection {
    
    private final CqlSession session;
    
    /**
     * Cria conexão com Cassandra
     * 
     * CONFIGURAÇÃO POR AMBIENTE VARIABLES:
     * - CASSANDRA_CONTACT_POINTS: IPs dos nós (default: "cassandra")
     * - CASSANDRA_PORT: Porta CQL (default: 9042)
     * - CASSANDRA_KEYSPACE: Keyspace a usar (default: "chat4all")
     * - CASSANDRA_DATACENTER: Nome do datacenter (default: "datacenter1")
     * 
     * EDUCATIONAL NOTE: Contact Points
     * - Driver conecta a QUALQUER nó do cluster
     * - Descobre automaticamente os outros nós (gossip protocol)
     * - Distribui queries entre todos os nós (load balancing)
     * - Se um nó cai, usa os outros (failover automático)
     * 
     * Exemplo: 3 nós - contactPoint = node1
     * ```
     * App → node1 (connect) → Descobre: node1, node2, node3
     *       ↓
     * App → [node1, node2, node3] (queries distribuídas)
     * ```
     * 
     * CONSISTENCY LEVEL (não configurado aqui, usa default):
     * - ONE: 1 nó confirma (rápido, menos garantias)
     * - QUORUM: Maioria confirma (RF=3 → 2 nós)
     * - ALL: Todos confirmam (lento, máxima garantia)
     * 
     * Para este projeto: Usamos ONE para velocidade
     */
    public CassandraConnection() {
        // Lê configuração de variáveis de ambiente
        String contactPoints = System.getenv().getOrDefault("CASSANDRA_CONTACT_POINTS", "cassandra");
        int port = Integer.parseInt(System.getenv().getOrDefault("CASSANDRA_PORT", "9042"));
        String keyspace = System.getenv().getOrDefault("CASSANDRA_KEYSPACE", "chat4all");
        String datacenter = System.getenv().getOrDefault("CASSANDRA_DATACENTER", "dc1");
        
        System.out.println("Connecting to Cassandra:");
        System.out.println("  Contact Points: " + contactPoints);
        System.out.println("  Port: " + port);
        System.out.println("  Keyspace: " + keyspace);
        System.out.println("  Datacenter: " + datacenter);
        
        // Cria CqlSession (thread-safe, reutilizável)
        CqlSessionBuilder builder = CqlSession.builder();
        
        // Adiciona contact points (pode ser lista separada por vírgula)
        for (String host : contactPoints.split(",")) {
            builder.addContactPoint(new InetSocketAddress(host.trim(), port));
        }
        
        // Configura datacenter local (para queries prefer nós locais)
        builder.withLocalDatacenter(datacenter);
        
        // Configura keyspace (namespace)
        builder.withKeyspace(keyspace);
        
        // Cria sessão (conexão com pool)
        this.session = builder.build();
        
        System.out.println("✓ Connected to Cassandra cluster");
        
        // Registra shutdown hook para fechar conexão gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Closing Cassandra connection...");
            close();
        }));
    }
    
    /**
     * Retorna CqlSession para executar queries
     * 
     * THREAD-SAFE:
     * - CqlSession é thread-safe (pode usar de múltiplas threads)
     * - Pool gerencia concorrência automaticamente
     * 
     * REUTILIZAÇÃO:
     * - Não crie novo CassandraConnection para cada query!
     * - Crie UMA vez na aplicação, reutilize getSession()
     * 
     * USO:
     * ```java
     * CassandraConnection conn = new CassandraConnection();
     * CqlSession session = conn.getSession();
     * 
     * // Thread 1
     * session.execute("INSERT INTO messages ...");
     * 
     * // Thread 2 (ao mesmo tempo)
     * session.execute("SELECT * FROM messages ...");
     * 
     * // Pool gerencia as 2 queries concorrentemente
     * ```
     * 
     * @return CqlSession thread-safe
     */
    public CqlSession getSession() {
        return session;
    }
    
    /**
     * Fecha conexão com Cassandra
     * 
     * GRACEFUL SHUTDOWN:
     * - Aguarda queries em andamento terminarem
     * - Fecha todas as conexões TCP do pool
     * - Libera recursos do driver
     * 
     * QUANDO CHAMAR:
     * - Na aplicação: shutdown hook (já registrado no construtor)
     * - Em testes: @AfterAll ou try-with-resources
     * 
     * NÃO chamar no meio da aplicação (session vira inutilizável)
     */
    public void close() {
        if (session != null && !session.isClosed()) {
            session.close();
            System.out.println("✓ Cassandra connection closed");
        }
    }
}
