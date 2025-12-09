package chat4all.api.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CassandraMessageRepository - Repository para queries READ-ONLY de mensagens
 * 
 * PROPÓSITO EDUCACIONAL: Query-Driven Design + Pagination
 * ==================
 * 
 * CASSANDRA QUERY PATTERNS:
 * 
 * PRIMARY KEY = (conversation_id, timestamp)
 *   ↓
 * Partition Key: conversation_id → Distribui dados entre nós
 * Clustering Key: timestamp → Ordena dentro da partição
 * 
 * QUERY EFICIENTE:
 * ```sql
 * SELECT * FROM messages 
 * WHERE conversation_id = 'conv_123'  ← Partition key (obrigatório!)
 * ORDER BY timestamp ASC              ← Clustering key (grátis!)
 * LIMIT 50;                           ← Pagination
 * ```
 * 
 * QUERY INEFICIENTE (AVOID):
 * ```sql
 * SELECT * FROM messages 
 * WHERE sender_id = 'user_a';  ← Não é partition key! (full scan)
 * ```
 * 
 * PAGINATION STRATEGIES:
 * 
 * 1. LIMIT/OFFSET (implementado aqui para simplicidade):
 *    - Simples de entender
 *    - Problema: OFFSET alto = lento (Cassandra lê e descarta rows)
 *    - OK para Fase 1 educacional
 * 
 * 2. CURSOR-BASED (produção real):
 *    - Usa último timestamp como cursor
 *    - WHERE timestamp > ? LIMIT 50
 *    - Mais eficiente, sem OFFSET
 * 
 * @author Chat4All Educational Project
 */
public class CassandraMessageRepository {
    
    private final CqlSession session;
    private final PreparedStatement getMessagesStatement;
    
    /**
     * Cria repository com PreparedStatement
     * 
     * EDUCATIONAL NOTE: Pagination no Cassandra
     * 
     * Cassandra não tem OFFSET nativo!
     * Para simular offset, lemos LIMIT + OFFSET rows e descartamos as primeiras OFFSET.
     * 
     * Produção real: usar paging state (cursor automático do driver).
     * 
     * @param session CqlSession do CassandraConnection
     */
    public CassandraMessageRepository(CqlSession session) {
        this.session = session;
        
        // Query otimizada: usa partition key + clustering key (Phase 2: includes file fields)
        this.getMessagesStatement = session.prepare(
            "SELECT conversation_id, timestamp, message_id, sender_id, content, status, file_id, file_metadata " +
            "FROM messages " +
            "WHERE conversation_id = ? " +
            "ORDER BY timestamp ASC"
            // LIMIT aplicado dinamicamente no bind
        );
        
        System.out.println("✓ CassandraMessageRepository initialized");
    }
    
    /**
     * Busca mensagens de uma conversação com paginação
     * 
     * FLUXO:
     * 1. Query Cassandra: WHERE conversation_id = ? ORDER BY timestamp
     * 2. Fetch LIMIT + OFFSET rows (não há OFFSET nativo)
     * 3. Descartar primeiras OFFSET rows (simula pagination)
     * 4. Retornar até LIMIT rows
     * 
     * COMPLEXITY:
     * - Best case (offset=0): O(limit)
     * - Worst case (offset alto): O(limit + offset)
     * 
     * EDUCATIONAL NOTE: Cursor-based pagination seria O(limit) sempre:
     * ```java
     * WHERE conversation_id = ? AND timestamp > lastTimestamp LIMIT ?
     * ```
     * 
     * FORMATO DE RETORNO:
     * ```json
     * [
     *   {
     *     "message_id": "msg_123",
     *     "conversation_id": "conv_abc",
     *     "sender_id": "user_a",
     *     "content": "Hello!",
     *     "timestamp": 1700000000000,
     *     "status": "DELIVERED"
     *   }
     * ]
     * ```
     * 
     * @param conversationId ID da conversação (partition key)
     * @param limit Máximo de mensagens a retornar (default: 50, max: 100)
     * @param offset Quantas mensagens pular (default: 0)
     * @return Lista de mensagens como Maps (JSON-ready)
     */
    public List<Map<String, Object>> getMessages(String conversationId, int limit, int offset) {
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // Validação de parâmetros
        if (conversationId == null || conversationId.trim().isEmpty()) {
            throw new IllegalArgumentException("conversation_id cannot be null or empty");
        }
        
        // Limites de segurança (evitar queries muito grandes)
        int safeLimit = Math.min(Math.max(limit, 1), 100); // Entre 1 e 100
        int safeOffset = Math.max(offset, 0); // >= 0
        
        try {
            // Fetch limit + offset rows (Cassandra não tem OFFSET nativo)
            int fetchSize = safeLimit + safeOffset;
            
            ResultSet rs = session.execute(getMessagesStatement.bind(conversationId));
            
            // Processar resultados
            int rowIndex = 0;
            for (Row row : rs) {
                // Simular OFFSET: pular primeiras N rows
                if (rowIndex < safeOffset) {
                    rowIndex++;
                    continue;
                }
                
                // Parar após LIMIT rows
                if (messages.size() >= safeLimit) {
                    break;
                }
                
                // Converter Row → Map (JSON-ready)
                Map<String, Object> message = new HashMap<>();
                message.put("message_id", row.getString("message_id"));
                message.put("conversation_id", row.getString("conversation_id"));
                message.put("sender_id", row.getString("sender_id"));
                message.put("content", row.getString("content"));
                message.put("status", row.getString("status"));
                
                // Timestamp: converter para epoch millis (compatível com frontend)
                Instant timestamp = row.getInstant("timestamp");
                if (timestamp != null) {
                    message.put("timestamp", timestamp.toEpochMilli());
                }
                
                // Phase 2: File attachment metadata
                String fileId = row.getString("file_id");
                if (fileId != null && !fileId.isEmpty()) {
                    message.put("file_id", fileId);
                    
                    // File metadata map
                    Map<String, String> fileMetadata = row.getMap("file_metadata", String.class, String.class);
                    if (fileMetadata != null && !fileMetadata.isEmpty()) {
                        // Extrair file_name e file_size para o nível principal
                        String fileName = fileMetadata.get("file_name");
                        String fileSize = fileMetadata.get("file_size");
                        
                        if (fileName != null) {
                            message.put("file_name", fileName);
                        }
                        if (fileSize != null && !fileSize.isEmpty()) {
                            try {
                                message.put("file_size", Long.parseLong(fileSize));
                            } catch (NumberFormatException e) {
                                System.err.println("[GetMessages] Error parsing file_size: " + fileSize);
                                message.put("file_size", 0L);
                            }
                        }
                    }
                }
                
                messages.add(message);
                rowIndex++;
            }
            
            System.out.println("✓ Retrieved " + messages.size() + " messages for conversation " + conversationId +
                             " (limit=" + safeLimit + ", offset=" + safeOffset + ")");
            
            return messages;
            
        } catch (Exception e) {
            System.err.println("✗ Failed to retrieve messages for " + conversationId + ": " + e.getMessage());
            throw new RuntimeException("Failed to query messages", e);
        }
    }
    
    /**
     * Get message by ID (Phase 8: Status Lifecycle)
     * 
     * @param messageId Message ID to query
     * @return Map with message data, or null if not found
     */
    public Map<String, Object> getMessageById(String messageId) {
        String query = "SELECT conversation_id, timestamp, message_id, sender_id, content, status, " +
                      "delivered_at, read_at, file_id, file_metadata " +
                      "FROM chat4all.messages WHERE message_id = ? ALLOW FILTERING";
        
        PreparedStatement statement = session.prepare(query);
        ResultSet resultSet = session.execute(statement.bind(messageId));
        Row row = resultSet.one();
        
        if (row == null) {
            return null;
        }
        
        Map<String, Object> message = new HashMap<>();
        message.put("conversation_id", row.getString("conversation_id"));
        message.put("timestamp", row.getInstant("timestamp"));
        message.put("message_id", row.getString("message_id"));
        message.put("sender_id", row.getString("sender_id"));
        message.put("content", row.getString("content"));
        message.put("status", row.getString("status"));
        message.put("delivered_at", row.getInstant("delivered_at"));
        message.put("read_at", row.getInstant("read_at"));
        message.put("file_id", row.getString("file_id"));
        
        return message;
    }
    
    /**
     * Update message status to READ (Phase 8: Status Lifecycle)
     * 
     * EDUCATIONAL NOTE: Two-step process required for Cassandra
     * 1. Query by message_id (secondary index) to get full primary key
     * 2. Update using conversation_id + timestamp (partition + clustering key)
     * 
     * @param messageId Message ID to update
     * @param status New status ("READ")
     * @param readAt Read timestamp (epoch millis)
     */
    public void updateMessageStatus(String messageId, String status, long readAt) {
        // Step 1: Query to get primary key components
        String selectQuery = "SELECT conversation_id, timestamp FROM chat4all.messages " +
                           "WHERE message_id = ? ALLOW FILTERING";
        PreparedStatement selectStmt = session.prepare(selectQuery);
        ResultSet resultSet = session.execute(selectStmt.bind(messageId));
        Row row = resultSet.one();
        
        if (row == null) {
            System.err.println("✗ Message not found: " + messageId);
            return;
        }
        
        String conversationId = row.getString("conversation_id");
        Instant messageTimestamp = row.getInstant("timestamp");
        
        // Step 2: Update using full primary key
        String update = "UPDATE chat4all.messages " +
                       "SET status = ?, read_at = ? " +
                       "WHERE conversation_id = ? AND timestamp = ?";
        
        PreparedStatement updateStmt = session.prepare(update);
        Instant readAtInstant = Instant.ofEpochMilli(readAt);
        
        session.execute(updateStmt.bind(status, readAtInstant, conversationId, messageTimestamp));
        
        System.out.println("✓ Updated message status: " + messageId + " → " + status);
    }
    
    /**
     * Conta total de mensagens em uma conversação
     * 
     * EDUCATIONAL NOTE: COUNT Query Performance
     * 
     * Cassandra COUNT(*) é LENTO!
     * - Precisa escanear toda a partição
     * - Não há contador mágico pré-calculado
     * 
     * Produção real: manter counter separado em outra tabela
     * ```sql
     * CREATE TABLE conversation_stats (
     *   conversation_id TEXT PRIMARY KEY,
     *   message_count COUNTER
     * );
     * UPDATE conversation_stats SET message_count = message_count + 1 WHERE ...;
     * ```
     * 
     * Fase 1: Simplificação - não implementamos count (client pode inferir se retornou < limit)
     * 
     * @param conversationId ID da conversação
     * @return Total de mensagens (aproximado)
     */
    public long countMessages(String conversationId) {
        // Simplified: retorna -1 (não implementado para Fase 1)
        // Em produção: usar COUNTER table
        return -1;
    }
    
    // ========================================================================
    // GROUP OPERATIONS (for gRPC GroupService)
    // ========================================================================
    
    /**
     * Group domain model
     */
    public static class Group {
        private String groupId;
        private String name;
        private List<String> participantIds;
        private String type;
        private long createdAt;
        
        public Group(String groupId, String name, List<String> participantIds, String type, long createdAt) {
            this.groupId = groupId;
            this.name = name;
            this.participantIds = participantIds;
            this.type = type;
            this.createdAt = createdAt;
        }
        
        public String getGroupId() { return groupId; }
        public String getName() { return name; }
        public List<String> getParticipantIds() { return participantIds; }
        public String getType() { return type; }
        public long getCreatedAt() { return createdAt; }
    }
    
    public void createGroup(String groupId, String name, List<String> participantIds, String type) {
        System.out.println("[createGroup] Criando grupo:");
        System.out.println("  - groupId: " + groupId);
        System.out.println("  - name: " + name);
        System.out.println("  - type: " + type);
        System.out.println("  - participants: " + participantIds);
        
        String query = "INSERT INTO chat4all.conversations (conversation_id, type, participant_ids, created_at) VALUES (?, ?, ?, ?)";
        PreparedStatement statement = session.prepare(query);
        Instant now = Instant.now();
        session.execute(statement.bind(groupId, type, participantIds, now));
        System.out.println("[createGroup] ✓ Inserido em conversations");

        // Persist metadata (name, members) in group_conversations para notificações e UI
        String groupQuery = "INSERT INTO chat4all.group_conversations (group_id, name, member_ids, created_at) VALUES (?, ?, ?, ?)";
        PreparedStatement groupStmt = session.prepare(groupQuery);
        session.execute(groupStmt.bind(groupId, name, participantIds, now));
        System.out.println("[createGroup] ✓ Inserido em group_conversations");

        System.out.println("✓ Group created: " + groupId + " with " + participantIds.size() + " participants");
    }
    
    public void addParticipantToGroup(String groupId, String userId) {
        // 1. Get current participants
        String selectQuery = "SELECT participant_ids FROM chat4all.conversations WHERE conversation_id = ?";
        PreparedStatement selectStmt = session.prepare(selectQuery);
        ResultSet rs = session.execute(selectStmt.bind(groupId));
        Row row = rs.one();
        
        if (row == null) {
            throw new IllegalArgumentException("Group not found: " + groupId);
        }
        
        List<String> participants = row.getList("participant_ids", String.class);
        if (participants == null) {
            participants = new ArrayList<>();
        }
        
        // 2. Add new participant if not already in group
        if (!participants.contains(userId)) {
            participants.add(userId);
            
            // 3. Update group with new participant list
            String updateQuery = "UPDATE chat4all.conversations SET participant_ids = ? WHERE conversation_id = ?";
            PreparedStatement updateStmt = session.prepare(updateQuery);
            session.execute(updateStmt.bind(participants, groupId));
            
            System.out.println("✓ Participant added to group: " + userId + " → " + groupId);
        } else {
            System.out.println("⚠️  Participant already in group: " + userId);
        }
    }
    
    public void removeParticipantFromGroup(String groupId, String userId) {
        // 1. Get current participants
        String selectQuery = "SELECT participant_ids FROM chat4all.conversations WHERE conversation_id = ?";
        PreparedStatement selectStmt = session.prepare(selectQuery);
        ResultSet rs = session.execute(selectStmt.bind(groupId));
        Row row = rs.one();
        
        if (row == null) {
            throw new IllegalArgumentException("Group not found: " + groupId);
        }
        
        List<String> participants = row.getList("participant_ids", String.class);
        if (participants == null) {
            participants = new ArrayList<>();
        }
        
        // 2. Remove participant
        if (participants.remove(userId)) {
            // 3. Update group with new participant list
            String updateQuery = "UPDATE chat4all.conversations SET participant_ids = ? WHERE conversation_id = ?";
            PreparedStatement updateStmt = session.prepare(updateQuery);
            session.execute(updateStmt.bind(participants, groupId));
            
            System.out.println("✓ Participant removed from group: " + userId + " ← " + groupId);
        } else {
            System.out.println("⚠️  Participant not in group: " + userId);
        }
    }
    
    public java.util.Optional<Group> getGroup(String groupId) {
        String query = "SELECT conversation_id, type, participant_ids, created_at FROM chat4all.conversations WHERE conversation_id = ?";
        PreparedStatement statement = session.prepare(query);
        ResultSet rs = session.execute(statement.bind(groupId));
        Row row = rs.one();
        
        if (row == null) {
            return java.util.Optional.empty();
        }
        
        List<String> participantIds = row.getList("participant_ids", String.class);
        Instant createdAt = row.getInstant("created_at");
        
        String name = groupId;
        try {
            PreparedStatement groupNameStmt = session.prepare(
                "SELECT name FROM chat4all.group_conversations WHERE group_id = ? LIMIT 1"
            );
            ResultSet nameRs = session.execute(groupNameStmt.bind(groupId));
            Row nameRow = nameRs.one();
            if (nameRow != null && nameRow.getString("name") != null) {
                name = nameRow.getString("name");
            }
        } catch (Exception e) {
            // fallback para groupId
        }

        Group group = new Group(
            row.getString("conversation_id"),
            name,
            participantIds != null ? participantIds : new ArrayList<>(),
            row.getString("type"),
            createdAt != null ? createdAt.toEpochMilli() : System.currentTimeMillis()
        );
        
        return java.util.Optional.of(group);
    }
    
    public List<Group> getUserGroups(String userId) {
        System.out.println("[getUserGroups] Buscando grupos para userId: " + userId);
        
        // Query conversations where user is participant
        String query = "SELECT conversation_id, type, participant_ids, created_at FROM chat4all.conversations WHERE participant_ids CONTAINS ? ALLOW FILTERING";
        PreparedStatement statement = session.prepare(query);
        ResultSet rs = session.execute(statement.bind(userId));

        PreparedStatement groupNameStmt = session.prepare(
            "SELECT name FROM chat4all.group_conversations WHERE group_id = ? LIMIT 1"
        );
        
        List<Group> groups = new ArrayList<>();
        int totalConversations = 0;
        int groupConversations = 0;
        
        for (Row row : rs) {
            totalConversations++;
            String conversationId = row.getString("conversation_id");
            System.out.println("[getUserGroups] Encontrada conversa: " + conversationId);
            
            // Only return groups (not direct conversations)
            if (conversationId != null && conversationId.startsWith("group_")) {
                groupConversations++;
                List<String> participantIds = row.getList("participant_ids", String.class);
                Instant createdAt = row.getInstant("created_at");

                String name = conversationId;
                try {
                    ResultSet nameRs = session.execute(groupNameStmt.bind(conversationId));
                    Row nameRow = nameRs.one();
                    if (nameRow != null && nameRow.getString("name") != null) {
                        name = nameRow.getString("name");
                    }
                } catch (Exception e) {
                    // fallback already set
                }
                
                Group group = new Group(
                    conversationId,
                    name,
                    participantIds != null ? participantIds : new ArrayList<>(),
                    row.getString("type"),
                    createdAt != null ? createdAt.toEpochMilli() : System.currentTimeMillis()
                );
                
                groups.add(group);
            }
        }
        
        System.out.println("[getUserGroups] Total conversas encontradas: " + totalConversations);
        System.out.println("[getUserGroups] Grupos encontrados: " + groupConversations);
        System.out.println("[getUserGroups] Retornando " + groups.size() + " grupos");
        
        return groups;
    }
    
    // ========================================================================
    // USER OPERATIONS (for gRPC AuthService)
    // ========================================================================
    
    public void createUser(String userId, String username, String email, String passwordHash) {
        String insert = "INSERT INTO chat4all.users (user_id, username, email, password, created_at) VALUES (?, ?, ?, ?, ?)";
        PreparedStatement stmt = session.prepare(insert);
        session.execute(stmt.bind(userId, username, email, passwordHash, Instant.now()));
    }
    
    public java.util.Optional<Map<String, Object>> getUserByUsername(String username) {
        String query = "SELECT user_id, username, email, password, created_at FROM chat4all.users WHERE username = ? LIMIT 1 ALLOW FILTERING";
        PreparedStatement stmt = session.prepare(query);
        ResultSet rs = session.execute(stmt.bind(username));
        Row row = rs.one();
        
        if (row == null) {
            return java.util.Optional.empty();
        }
        
        Map<String, Object> user = new HashMap<>();
        user.put("user_id", row.getString("user_id"));
        user.put("username", row.getString("username"));
        user.put("email", row.getString("email"));
        user.put("password_hash", row.getString("password")); // Column is 'password', return as 'password_hash'
        user.put("created_at", row.getInstant("created_at").toEpochMilli());
        
        return java.util.Optional.of(user);
    }
    
    /**
     * Get all users (for user list in web interface)
     */
    public List<Map<String, Object>> getAllUsers() {
        String query = "SELECT user_id, username, email, created_at FROM chat4all.users";
        PreparedStatement stmt = session.prepare(query);
        ResultSet rs = session.execute(stmt.bind());
        
        List<Map<String, Object>> users = new ArrayList<>();
        for (Row row : rs) {
            Map<String, Object> user = new HashMap<>();
            user.put("userId", row.getString("user_id"));
            user.put("username", row.getString("username"));
            user.put("email", row.getString("email"));
            user.put("createdAt", row.getInstant("created_at").toEpochMilli());
            users.add(user);
        }
        
        return users;
    }
    
    /**
     * Save message directly (for REST API bypass)
     */
    public void saveMessageDirect(String messageId, String conversationId, String senderId, String content, long timestamp) {
        saveMessageDirect(messageId, conversationId, senderId, content, timestamp, null, null);
    }
    
    public void saveMessageDirect(String messageId, String conversationId, String senderId, String content, long timestamp, String fileId, Map<String, String> fileMetadata) {
        String query;
        PreparedStatement stmt;
        
        if (fileId != null && !fileId.isEmpty()) {
            // INSERT com file_id e file_metadata
            query = "INSERT INTO chat4all.messages " +
                "(conversation_id, timestamp, message_id, sender_id, content, status, file_id, file_metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            
            stmt = session.prepare(query);
            session.execute(stmt.bind(
                conversationId,
                java.time.Instant.ofEpochMilli(timestamp),
                messageId,
                senderId,
                content,
                "SENT",
                fileId,
                fileMetadata
            ));
        } else {
            // INSERT sem file_id
            query = "INSERT INTO chat4all.messages " +
                "(conversation_id, timestamp, message_id, sender_id, content, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
            
            stmt = session.prepare(query);
            session.execute(stmt.bind(
                conversationId,
                java.time.Instant.ofEpochMilli(timestamp),
                messageId,
                senderId,
                content,
                "SENT"
            ));
        }
    }
}

