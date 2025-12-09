package chat4all.shared.connector;

import chat4all.shared.MessageEvent;

/**
 * Interface padrão para todos os conectores de terceiros
 * (WhatsApp, Instagram, Telegram, etc)
 * 
 * PROPÓSITO: Definir contrato comum para todos os conectores
 * e permitir adicionar novos conectores facilmente
 * 
 * PADRÃO APLICADO: Strategy Pattern
 * - Cada conector é uma "estratégia" de entrega
 * - ConectorRouter escolhe qual conector usar baseado no tipo
 * 
 * IMPLEMENTA RN-005: Interface padronizada (sendText, sendFile, onWebhookEvent)
 * 
 * EXEMPLO:
 * ```java
 * // Antes (sem interface):
 * if (platform.equals("whatsapp")) {
 *     new WhatsAppConnector().sendMessage(message);
 * } else if (platform.equals("instagram")) {
 *     new InstagramConnector().sendMessage(message);
 * }
 * 
 * // Depois (com interface):
 * PlatformConnector connector = connectorFactory.get(platform);
 * connector.sendText(conversationId, content); // Polimorfismo!
 * ```
 * 
 * CONECTORES IMPLEMENTADOS:
 * - WhatsApp Connector: Integração com WhatsApp Business API
 * - Instagram Connector: Integração com Instagram Messaging API
 * - (Futuros) Telegram, Email, SMS, etc
 * 
 * @author Chat4All Team
 * @version 2.0.0 (RN-005 Implementado)
 */
public interface PlatformConnector {
    
    /**
     * Retorna identificador único do conector
     * 
     * @return identificador (ex: "whatsapp", "instagram", "telegram")
     */
    String getConnectorId();
    
    /**
     * Verifica se conector está saudável
     * 
     * @return true se operacional, false se degradado/erro
     */
    boolean isHealthy();
    
    /**
     * Envia mensagem de TEXTO (RN-005)
     * 
     * MÉTODO ESPECÍFICO para mensagens de texto simples.
     * Mais semântico que sendMessage() genérico.
     * 
     * @param conversationId ID da conversa
     * @param content Conteúdo da mensagem (texto)
     * @return true se enviado com sucesso
     * @throws ConnectorException se erro ao enviar
     */
    boolean sendText(String conversationId, String content) throws ConnectorException;
    
    /**
     * Envia ARQUIVO (RN-005)
     * 
     * MÉTODO ESPECÍFICO para arquivos (imagens, vídeos, documentos).
     * Trata anexos de forma diferente de texto.
     * 
     * @param conversationId ID da conversa
     * @param fileId ID do arquivo (referência no MinIO)
     * @param fileMetadata Metadados (filename, size, mime_type)
     * @return true se enviado com sucesso
     * @throws ConnectorException se erro ao enviar
     */
    boolean sendFile(String conversationId, String fileId, java.util.Map<String, String> fileMetadata) 
        throws ConnectorException;
    
    /**
     * Processa evento de WEBHOOK recebido (RN-005)
     * 
     * Chamado quando plataforma externa envia callback.
     * Exemplos:
     * - WhatsApp: entrega confirmada, mensagem lida
     * - Instagram: nova mensagem recebida
     * 
     * @param event Evento recebido do webhook
     * @throws ConnectorException se erro ao processar
     */
    void onWebhookEvent(WebhookEvent event) throws ConnectorException;
    
    /**
     * Envia mensagem genérica (DEPRECATED - usar sendText ou sendFile)
     * 
     * Mantido para compatibilidade, mas RECOMENDADO usar métodos específicos.
     * 
     * @param message Mensagem a enviar
     * @return true se enviado com sucesso
     * @throws ConnectorException se erro ao enviar
     * @deprecated Use {@link #sendText} ou {@link #sendFile}
     */
    @Deprecated
    boolean sendMessage(MessageEvent message) throws ConnectorException;
    
    /**
     * Retorna nome amigável do conector
     * 
     * @return nome (ex: "WhatsApp Business", "Instagram Direct Messages")
     */
    String getName();
    
    /**
     * Retorna versão do conector
     * 
     * @return versão (ex: "2.0.0")
     */
    String getVersion();
    
    /**
     * Inicializa conector
     * Chamado uma vez na startup
     * 
     * @throws ConnectorException se falha ao inicializar
     */
    void initialize() throws ConnectorException;
    
    /**
     * Finaliza conector gracefully
     * Chamado no shutdown
     */
    void shutdown();
}
