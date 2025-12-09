package chat4all.shared.connector;

/**
 * Exceção padrão para erros em conectores
 * 
 * @author Chat4All Team
 */
public class ConnectorException extends Exception {
    
    private final String connectorId;
    private final String errorType;
    
    public ConnectorException(String connectorId, String message) {
        super(message);
        this.connectorId = connectorId;
        this.errorType = "UNKNOWN";
    }
    
    public ConnectorException(String connectorId, String errorType, String message) {
        super(message);
        this.connectorId = connectorId;
        this.errorType = errorType;
    }
    
    public ConnectorException(String connectorId, String message, Throwable cause) {
        super(message, cause);
        this.connectorId = connectorId;
        this.errorType = "UNKNOWN";
    }
    
    public ConnectorException(String connectorId, String errorType, String message, Throwable cause) {
        super(message, cause);
        this.connectorId = connectorId;
        this.errorType = errorType;
    }
    
    public String getConnectorId() {
        return connectorId;
    }
    
    public String getErrorType() {
        return errorType;
    }
}
