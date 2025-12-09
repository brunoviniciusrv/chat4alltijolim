package chat4all.connector.whatsapp;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO para mensagem específica do WhatsApp
 * 
 * PADRÃO: Value Object + Builder
 * 
 * RESPONSABILIDADE: Encapsular dados específicos do WhatsApp
 * 
 * EXEMPLO:
 * ```java
 * WhatsAppMessage msg = new WhatsAppMessage.Builder()
 *     .phoneNumber("+5511987654321")
 *     .content("Olá!")
 *     .senderId("user_alice")
 *     .messageId("msg_123")
 *     .build();
 * ```
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class WhatsAppMessage {
    
    private final String phoneNumber;      // Destinatário
    private final String content;          // Texto da mensagem
    private final String senderId;         // Quem enviou
    private final String messageId;        // ID único
    private final long timestamp;          // Quando foi criada
    private final Map<String, String> metadata; // Dados extras
    
    private WhatsAppMessage(Builder builder) {
        this.phoneNumber = builder.phoneNumber;
        this.content = builder.content;
        this.senderId = builder.senderId;
        this.messageId = builder.messageId;
        this.timestamp = builder.timestamp;
        this.metadata = new HashMap<>(builder.metadata);
    }
    
    // Getters
    public String getPhoneNumber() { return phoneNumber; }
    public String getContent() { return content; }
    public String getSenderId() { return senderId; }
    public String getMessageId() { return messageId; }
    public long getTimestamp() { return timestamp; }
    public Map<String, String> getMetadata() { return new HashMap<>(metadata); }
    
    /**
     * Converte para JSON para enviar via API
     * 
     * @return JSON string
     */
    public String toJson() {
        return "{" +
            "\"phoneNumber\":\"" + phoneNumber + "\"," +
            "\"content\":\"" + escapeJson(content) + "\"," +
            "\"senderId\":\"" + senderId + "\"," +
            "\"messageId\":\"" + messageId + "\"," +
            "\"timestamp\":" + timestamp +
            "}";
    }
    
    /**
     * Escape simples de JSON (produção deve usar biblioteca)
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
    
    @Override
    public String toString() {
        return "WhatsAppMessage{" +
                "phoneNumber='" + phoneNumber + '\'' +
                ", content='" + content + '\'' +
                ", senderId='" + senderId + '\'' +
                ", messageId='" + messageId + '\'' +
                '}';
    }
    
    /**
     * Builder fluente para criar mensagens
     */
    public static class Builder {
        private String phoneNumber;
        private String content;
        private String senderId;
        private String messageId;
        private long timestamp = System.currentTimeMillis();
        private final Map<String, String> metadata = new HashMap<>();
        
        public Builder phoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }
        
        public Builder content(String content) {
            this.content = content;
            return this;
        }
        
        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }
        
        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }
        
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder addMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public WhatsAppMessage build() {
            if (phoneNumber == null || phoneNumber.isEmpty()) {
                throw new IllegalArgumentException("phoneNumber is required");
            }
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("content is required");
            }
            if (messageId == null || messageId.isEmpty()) {
                throw new IllegalArgumentException("messageId is required");
            }
            
            return new WhatsAppMessage(this);
        }
    }
}
