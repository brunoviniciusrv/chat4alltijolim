package chat4all.shared.connector;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory padrão para criar e gerenciar conectores
 * 
 * PADRÃO: Factory Pattern
 * PROPÓSITO: Centralizar criação de conectores e permitir registro/descoberta dinâmica
 * 
 * BENEFÍCIOS:
 * - Desacoplamento: ConectorRouter não precisa conhecer implementações
 * - Extensibilidade: Novos conectores registram-se automaticamente
 * - Testabilidade: Fácil substituir com mocks
 * 
 * EXEMPLO DE USO:
 * ```java
 * // Registrar conectores na startup
 * ConnectorFactory factory = new ConnectorFactory();
 * factory.register("whatsapp", () -> new WhatsAppConnector());
 * factory.register("instagram", () -> new InstagramConnector());
 * 
 * // Usar depois
 * PlatformConnector connector = factory.create("whatsapp");
 * ```
 * 
 * VANTAGEM SOBRE CÓDIGO ANTIGO:
 * Antes: if/else gigante em ConnectorRouter
 * Depois: Factory centraliza lógica, fácil de estender
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class ConnectorFactory {
    
    private final Map<String, ConnectorCreator> creators = new HashMap<>();
    
    /**
     * Interface funcional para criar conectores
     */
    @FunctionalInterface
    public interface ConnectorCreator {
        PlatformConnector create() throws ConnectorException;
    }
    
    /**
     * Registra novo conector no factory
     * 
     * @param connectorId Identificador único (ex: "whatsapp", "instagram")
     * @param creator Lambda/Functional reference para criar instância
     */
    public void register(String connectorId, ConnectorCreator creator) {
        creators.put(connectorId, creator);
        System.out.println("✅ Connector registered: " + connectorId);
    }
    
    /**
     * Cria nova instância de conector
     * 
     * @param connectorId Identificador do conector
     * @return Nova instância do conector
     * @throws ConnectorException se conector não encontrado ou erro ao criar
     */
    public PlatformConnector create(String connectorId) throws ConnectorException {
        ConnectorCreator creator = creators.get(connectorId);
        
        if (creator == null) {
            throw new ConnectorException(
                connectorId,
                "NOT_FOUND",
                "Connector not registered: " + connectorId
            );
        }
        
        try {
            PlatformConnector connector = creator.create();
            connector.initialize();
            return connector;
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException(
                connectorId,
                "CREATION_ERROR",
                "Failed to create connector: " + e.getMessage(),
                e
            );
        }
    }
    
    /**
     * Retorna lista de conectores disponíveis
     * 
     * @return array de IDs de conectores
     */
    public String[] getAvailableConnectors() {
        return creators.keySet().toArray(new String[0]);
    }
    
    /**
     * Verifica se conector está registrado
     * 
     * @param connectorId Identificador
     * @return true se registrado
     */
    public boolean isRegistered(String connectorId) {
        return creators.containsKey(connectorId);
    }
    
    /**
     * Extract platform from recipient ID (static utility)
     * 
     * Used by ConnectorRouter to determine which Kafka topic to route to
     * 
     * Examples:
     * - "whatsapp:+5511999999999" → "whatsapp"
     * - "instagram:@maria_silva" → "instagram"
     * - "user_123" → null (local delivery)
     * 
     * @param recipientId Recipient identifier
     * @return Platform name, or null if no platform prefix found
     */
    public static String getPlatformFromRecipientId(String recipientId) {
        if (recipientId == null || recipientId.isEmpty()) {
            return null;
        }
        
        // Split by ":" to extract platform prefix
        int colonIndex = recipientId.indexOf(':');
        if (colonIndex <= 0) {
            return null; // No platform prefix
        }
        
        return recipientId.substring(0, colonIndex);
    }
}
