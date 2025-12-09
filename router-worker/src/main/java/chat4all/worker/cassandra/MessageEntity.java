package chat4all.worker.cassandra;

import java.time.Instant;
import java.util.UUID;

/**
 * MessageEntity - Representa uma mensagem persistida no Cassandra
 * 
 * PROPÓSITO EDUCACIONAL: Data Model + Cassandra Schema Mapping
 * ==================
 * 
 * SCHEMA CASSANDRA (cassandra-init/schema.cql):
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
 * PARTITION KEY vs CLUSTERING KEY:
 * - Partition Key (conversation_id): Distribui dados entre nós
 * - Clustering Key (timestamp): Ordena dados DENTRO da partição
 * 
 * EXEMPLO: 3 mensagens na conversação "conv_abc"
 * ```
 * Nó 1 (partition: conv_abc)
 *   ├─ timestamp: 2024-01-01T10:00:00 → msg_001 (SENT)
 *   ├─ timestamp: 2024-01-01T10:01:00 → msg_002 (DELIVERED)
 *   └─ timestamp: 2024-01-01T10:02:00 → msg_003 (SENT)
 * ```
 * 
 * POR QUE ESSA ESTRUTURA?
 * - Query eficiente: "Buscar mensagens de conv_abc ordenadas por tempo"
 * - INSERT rápido: Append-only (adiciona no fim da partição)
 * - UPDATE status: Busca por message_id (chave primária secundária)
 * 
 * STATUS POSSÍVEIS:
 * - SENT: Mensagem recebida da API, salva no banco
 * - DELIVERED: Worker simulou entrega (sleep 100ms)
 * - READ: (futuro) Destinatário leu a mensagem
 * 
 * @author Chat4All Educational Project
 */
public class MessageEntity {
    
    private final String conversationId;
    private final Instant timestamp;
    private final String messageId;
    private final String senderId;
    private final String content;
    private String status;
    private final String fileId; // Phase 2: File attachment support
    private final java.util.Map<String, String> fileMetadata; // Phase 2: File metadata
    
    /**
     * Construtor completo
     * 
     * @param conversationId ID da conversação (partition key)
     * @param timestamp Momento de criação (clustering key)
     * @param messageId ID único da mensagem (primary key adicional)
     * @param senderId Quem enviou
     * @param content Texto da mensagem (max 10KB na validação da API)
     * @param status SENT, DELIVERED, ou READ
     */
    public MessageEntity(String conversationId, Instant timestamp, String messageId, 
                         String senderId, String content, String status) {
        this(conversationId, timestamp, messageId, senderId, content, status, null, null);
    }
    
    /**
     * Construtor com suporte a anexos (Phase 2)
     * 
     * @param conversationId ID da conversação
     * @param timestamp Momento de criação
     * @param messageId ID único da mensagem
     * @param senderId Quem enviou
     * @param content Texto da mensagem
     * @param status Status da mensagem
     * @param fileId ID do arquivo anexado (opcional)
     * @param fileMetadata Metadata do arquivo (opcional)
     */
    public MessageEntity(String conversationId, Instant timestamp, String messageId, 
                         String senderId, String content, String status,
                         String fileId, java.util.Map<String, String> fileMetadata) {
        this.conversationId = conversationId;
        this.timestamp = timestamp;
        this.messageId = messageId;
        this.senderId = senderId;
        this.content = content;
        this.status = status;
        this.fileId = fileId;
        this.fileMetadata = fileMetadata;
    }
    
    // Getters
    
    public String getConversationId() {
        return conversationId;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public String getMessageId() {
        return messageId;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public String getContent() {
        return content;
    }
    
    public String getStatus() {
        return status;
    }
    
    public String getFileId() {
        return fileId;
    }
    
    public java.util.Map<String, String> getFileMetadata() {
        return fileMetadata;
    }
    
    // Setter apenas para status (único campo mutável)
    
    /**
     * Atualiza status da mensagem
     * 
     * TRANSIÇÕES VÁLIDAS:
     * SENT → DELIVERED → READ
     * 
     * NÃO permitir: DELIVERED → SENT (regressão)
     * 
     * @param status Novo status
     */
    public void setStatus(String status) {
        this.status = status;
    }
    
    @Override
    public String toString() {
        return "MessageEntity{" +
                "conversationId='" + conversationId + '\'' +
                ", timestamp=" + timestamp +
                ", messageId='" + messageId + '\'' +
                ", senderId='" + senderId + '\'' +
                ", content='" + (content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
