package chat4all.worker.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;

import java.time.Instant;

/**
 * CassandraMessageStore - Persistência de mensagens no Cassandra
 * 
 * PROPÓSITO EDUCACIONAL: Data Access Pattern + PreparedStatements
 * ==================
 * 
 * O QUE SÃO PREPAREDSTATEMENTS?
 * - Query pré-compilada pelo Cassandra
 * - Reutilizável com diferentes parâmetros
 * - Mais rápido: evita parse da query a cada execução
 * - Mais seguro: previne CQL injection
 * 
 * EXEMPLO SEM PreparedStatement (lento, inseguro):
 * ```java
 * session.execute("INSERT INTO messages VALUES ('" + convId + "', '" + content + "')");
 * // Problema: Se content = "'); DROP TABLE messages; --" → CQL injection!
 * // Problema: Parse da query a cada INSERT (overhead)
 * ```
 * 
 * EXEMPLO COM PreparedStatement (rápido, seguro):
 * ```java
 * PreparedStatement stmt = session.prepare("INSERT INTO messages VALUES (?, ?)");
 * session.execute(stmt.bind(convId, content));
 * // ✓ Parâmetros são escapados automaticamente
 * // ✓ Query parseada 1 vez, reutilizada N vezes
 * ```
 * 
 * OPERATIONS:
 * - saveMessage(): INSERT com status SENT
 * - updateMessageStatus(): UPDATE status (SENT → DELIVERED)
 * - messageExists(): SELECT para deduplicação
 * 
 * SCHEMA REMINDER:
 * ```sql
 * CREATE TABLE messages (
 *     conversation_id text,
 *     timestamp timestamp,
 *     message_id text PRIMARY KEY,
 *     sender_id text,
 *     content text,
 *     status text
 * );
 * ```
 * 
 * @author Chat4All Educational Project
 */
public class CassandraMessageStore {
    
    private final CqlSession session;
    private final PreparedStatement insertStatement;
    private final PreparedStatement updateStatusStatement;
    private final PreparedStatement checkExistsStatement;
    private final PreparedStatement getMessageStatement;
    private final PreparedStatement getUsernameStatement;
    private final PreparedStatement getGroupNameStatement;
    private final PreparedStatement getGroupMembersStatement;
    
    /**
     * Cria CassandraMessageStore com PreparedStatements
     * 
     * PERFORMANCE NOTE:
     * - PreparedStatements criados UMA vez no construtor
     * - Reutilizados milhares de vezes durante vida da aplicação
     * - Cassandra cacheia query plan (otimização)
     * 
     * @param session CqlSession do CassandraConnection
     */
    public CassandraMessageStore(CqlSession session) {
        this.session = session;
        
        // Prepara INSERT statement (Phase 2: includes file_id and file_metadata)
        // EDUCATIONAL NOTE: ? são placeholders para parâmetros
        this.insertStatement = session.prepare(
            "INSERT INTO messages (conversation_id, timestamp, message_id, sender_id, content, status, file_id, file_metadata) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        );
        
        // Prepara UPDATE statement
        // NOTA: Precisamos de conversation_id e timestamp porque são a PRIMARY KEY
        // message_id não está na primary key, então fazemos um SELECT primeiro
        this.updateStatusStatement = session.prepare(
            "UPDATE messages SET status = ? WHERE conversation_id = ? AND timestamp = ?"
        );
        
        // Prepara SELECT para buscar conversation_id e timestamp por message_id
        this.getMessageStatement = session.prepare(
            "SELECT conversation_id, timestamp FROM messages WHERE message_id = ? LIMIT 1 ALLOW FILTERING"
        );
        
        // Prepara SELECT para verificar existência (deduplicação)
        this.checkExistsStatement = session.prepare(
            "SELECT message_id FROM messages WHERE message_id = ? LIMIT 1 ALLOW FILTERING"
        );

        this.getUsernameStatement = session.prepare(
            "SELECT username FROM users WHERE user_id = ? LIMIT 1"
        );

        this.getGroupNameStatement = session.prepare(
            "SELECT name FROM group_conversations WHERE group_id = ? LIMIT 1"
        );

        this.getGroupMembersStatement = session.prepare(
            "SELECT member_ids FROM group_conversations WHERE group_id = ? LIMIT 1"
        );
        
        System.out.println("✓ CassandraMessageStore initialized with PreparedStatements");
    }
    
    /**
     * Salva mensagem no Cassandra
     * 
     * FLUXO:
     * 1. Bind parâmetros ao PreparedStatement
     * 2. Execute INSERT
     * 3. Cassandra distribui dado baseado em partition key (conversation_id)
     * 4. Dado replicado em RF nós (Replication Factor)
     * 
     * CONSISTENCY LEVEL (default: ONE):
     * - 1 nó confirma write antes de retornar
     * - Trade-off: Latência baixa, menor garantia
     * - Para produção: considerar QUORUM (maioria dos nós)
     * 
     * EDUCATIONAL NOTE: Write Path no Cassandra
     * ```
     * App → Coordinator Node → Hash(conversation_id) → Primary Node
     *                              ↓
     *                     Replica Node 1, Replica Node 2
     *                              ↓
     *                     Commit Log (durabilidade)
     *                              ↓
     *                     Memtable (in-memory)
     *                              ↓
     *                     SSTable (flush para disco)
     * ```
     * 
     * @param message MessageEntity a ser salva
     * @return true se salvou, false se erro
     */
    public boolean saveMessage(MessageEntity message) {
        try {
            // Bind parâmetros ao PreparedStatement (Phase 2: includes file fields)
            session.execute(insertStatement.bind(
                message.getConversationId(),
                message.getTimestamp(),
                message.getMessageId(),
                message.getSenderId(),
                message.getContent(),
                message.getStatus(),
                message.getFileId(),         // Phase 2: file attachment
                message.getFileMetadata()    // Phase 2: file metadata map
            ));
            
            String fileInfo = message.getFileId() != null ? 
                " [file: " + message.getFileId() + "]" : "";
            System.out.println("✓ Saved message: " + message.getMessageId() + 
                             " (conv: " + message.getConversationId() + ")" + fileInfo);
            return true;
            
        } catch (Exception e) {
            System.err.println("✗ Failed to save message " + message.getMessageId() + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Atualiza status da mensagem
     * 
     * TRANSIÇÕES VÁLIDAS:
     * SENT → DELIVERED → READ
     * 
     * CONSISTENCY:
     * - UPDATE é idempotente: executar 2x não muda resultado
     * - Se rede falhar e retry, não corrompe dado
     * 
     * EDUCATIONAL NOTE: Update Path
     * - Cassandra NÃO faz update in-place (não é MySQL!)
     * - Escreve novo registro com timestamp mais recente
     * - Na leitura: retorna versão mais recente (last-write-wins)
     * - Compaction: eventualmente remove versões antigas
     * 
     * EDUCATIONAL NOTE: PRIMARY KEY Requirement
     * - UPDATE no Cassandra requer TODA a primary key
     * - Nossa PRIMARY KEY = (conversation_id, timestamp)
     * - message_id tem índice secundário, mas não é primary key
     * - Solução: Primeiro SELECT para buscar (conversation_id, timestamp)
     * 
     * @param messageId ID da mensagem
     * @param conversationId ID da conversação (parte da PK)
     * @param timestamp Timestamp da mensagem (parte da PK)
     * @param newStatus Novo status (SENT, DELIVERED, READ)
     * @return true se atualizou, false se erro
     */
    public boolean updateMessageStatus(String messageId, String conversationId, Instant timestamp, String newStatus) {
        try {
            session.execute(updateStatusStatement.bind(newStatus, conversationId, timestamp));
            
            System.out.println("✓ Updated message " + messageId + " status to: " + newStatus);
            return true;
            
        } catch (Exception e) {
            System.err.println("✗ Failed to update status for " + messageId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Verifica se mensagem já existe (deduplicação)
     * 
     * POR QUE PRECISAMOS DISSO?
     * - Kafka garante "at-least-once" delivery
     * - Se consumer crashar antes do commit, mensagem reprocessada
     * - Sem dedup: mensagem duplicada no banco
     * 
     * IDEMPOTÊNCIA:
     * - Processar mesma mensagem 2x = mesmo resultado que 1x
     * - Solução: Verificar message_id antes de inserir
     * 
     * EXEMPLO DE DUPLICAÇÃO:
     * ```
     * 1. Worker consome msg_123 do Kafka
     * 2. Salva no Cassandra
     * 3. CRASH antes de commitar offset
     * 4. Worker reinicia
     * 5. Kafka reenvia msg_123 (offset não commitado)
     * 6. messageExists() retorna true → SKIP reprocessamento
     * ```
     * 
     * PERFORMANCE:
     * - SELECT com LIMIT 1 (para assim que achar)
     * - message_id é PRIMARY KEY (índice, busca rápida)
     * 
     * @param messageId ID da mensagem a verificar
     * @return true se existe, false se não existe
     */
    public boolean messageExists(String messageId) {
        try {
            ResultSet rs = session.execute(checkExistsStatement.bind(messageId));
            Row row = rs.one();
            
            boolean exists = (row != null);
            
            if (exists) {
                System.out.println("⚠ Message " + messageId + " already exists (duplicate)");
            }
            
            return exists;
            
        } catch (Exception e) {
            System.err.println("✗ Failed to check existence for " + messageId + ": " + e.getMessage());
            // Em caso de erro, assumir que NÃO existe (fail open)
            // Melhor reprocessar do que perder mensagem
            return false;
        }
    }

    /**
     * Busca username a partir do user_id. Retorna null se não encontrar.
     */
    public String getUsername(String userId) {
        try {
            ResultSet rs = session.execute(getUsernameStatement.bind(userId));
            Row row = rs.one();
            if (row != null) {
                return row.getString("username");
            }
        } catch (Exception e) {
            System.err.println("✗ Failed to fetch username for " + userId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Busca o nome do grupo em group_conversations. Retorna null se não encontrar.
     */
    public String getGroupName(String groupId) {
        try {
            ResultSet rs = session.execute(getGroupNameStatement.bind(groupId));
            Row row = rs.one();
            if (row != null) {
                return row.getString("name");
            }
        } catch (Exception e) {
            System.err.println("✗ Failed to fetch group name for " + groupId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Busca os member_ids do grupo. Retorna null se não encontrar.
     */
    public java.util.List<String> getGroupMembers(String groupId) {
        try {
            ResultSet rs = session.execute(getGroupMembersStatement.bind(groupId));
            Row row = rs.one();
            if (row != null) {
                return row.getList("member_ids", String.class);
            }
        } catch (Exception e) {
            System.err.println("✗ Failed to fetch group members for " + groupId + ": " + e.getMessage());
        }
        return null;
    }
}
