package chat4all.api.auth;

/**
 * JwtAuthenticationException - Custom Exception for JWT Validation Failures
 * 
 * EDUCATIONAL PURPOSE:
 * ==================
 * This exception is thrown when JWT token validation fails.
 * 
 * WHEN TO THROW:
 * - Token expired (exp < current time)
 * - Invalid signature (tampered token)
 * - Malformed token (not 3 parts)
 * - Missing required claims
 * 
 * HTTP MAPPING:
 * - JwtAuthenticationException -> 401 Unauthorized
 * 
 * @author Chat4All Educational Project
 * @version 1.0.0
 */
public class JwtAuthenticationException extends RuntimeException {
    
    public JwtAuthenticationException(String message) {
        super(message);
    }
    
    public JwtAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
