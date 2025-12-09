package chat4all.shared.connector;

import java.util.Map;

/**
 * WebhookEvent - Representa evento recebido via webhook
 * 
 * PROPÓSITO: Padronizar eventos recebidos de plataformas externas
 * (WhatsApp, Instagram, etc) via webhooks
 * 
 * IMPLEMENTA RN-005: onWebhookEvent()
 * 
 * EXEMPLOS:
 * 
 * 1. WhatsApp - Mensagem entregue:
 * ```json
 * {
 *   "type": "MESSAGE_DELIVERED",
 *   "platform": "whatsapp",
 *   "messageId": "msg_123",
 *   "conversationId": "whatsapp_+5562996991812",
 *   "timestamp": 1733328000000,
 *   "data": {
 *     "status": "delivered",
 *     "phone_number": "+5562996991812"
 *   }
 * }
 * ```
 * 
 * 2. Instagram - Nova mensagem recebida:
 * ```json
 * {
 *   "type": "NEW_MESSAGE_RECEIVED",
 *   "platform": "instagram",
 *   "conversationId": "instagram_@user123",
 *   "timestamp": 1733328000000,
 *   "data": {
 *     "sender": "@user123",
 *     "text": "Olá!",
 *     "message_id": "ig_xyz789"
 *   }
 * }
 * ```
 * 
 * @author Chat4All Team
 * @version 2.0.0
 */
public class WebhookEvent {
    
    /** Tipo do evento */
    private final String type;
    
    /** Plataforma de origem (whatsapp, instagram, etc) */
    private final String platform;
    
    /** ID da mensagem (se aplicável) */
    private final String messageId;
    
    /** ID da conversa */
    private final String conversationId;
    
    /** Timestamp do evento (Unix epoch millis) */
    private final long timestamp;
    
    /** Dados adicionais do evento */
    private final Map<String, Object> data;
    
    public WebhookEvent(String type, String platform, String messageId, 
                       String conversationId, long timestamp, Map<String, Object> data) {
        this.type = type;
        this.platform = platform;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.timestamp = timestamp;
        this.data = data;
    }
    
    public String getType() { return type; }
    public String getPlatform() { return platform; }
    public String getMessageId() { return messageId; }
    public String getConversationId() { return conversationId; }
    public long getTimestamp() { return timestamp; }
    public Map<String, Object> getData() { return data; }
    
    /**
     * Tipos de eventos suportados
     */
    public static class EventType {
        // Eventos de status de mensagem
        public static final String MESSAGE_DELIVERED = "MESSAGE_DELIVERED";
        public static final String MESSAGE_READ = "MESSAGE_READ";
        public static final String MESSAGE_FAILED = "MESSAGE_FAILED";
        
        // Eventos de mensagens recebidas
        public static final String NEW_MESSAGE_RECEIVED = "NEW_MESSAGE_RECEIVED";
        public static final String NEW_FILE_RECEIVED = "NEW_FILE_RECEIVED";
        
        // Eventos de presença
        public static final String USER_TYPING = "USER_TYPING";
        public static final String USER_ONLINE = "USER_ONLINE";
        public static final String USER_OFFLINE = "USER_OFFLINE";
    }
    
    @Override
    public String toString() {
        return String.format("WebhookEvent{type='%s', platform='%s', messageId='%s', conversationId='%s'}",
                           type, platform, messageId, conversationId);
    }
}
