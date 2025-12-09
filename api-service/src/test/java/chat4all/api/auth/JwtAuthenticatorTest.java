package chat4all.api.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.*;

/**
 * JwtAuthenticatorTest - Unit Tests for JWT Token Validation
 * 
 * EDUCATIONAL PURPOSE: Test-First Development (TDD)
 * ==================
 * Tests JWT validation logic BEFORE implementing JwtAuthenticator.java
 * 
 * WHY TEST JWT VALIDATION?
 * - Security critical: Invalid tokens must be rejected
 * - Signature verification: HMAC-SHA256 with secret key
 * - Expiration check: Expired tokens are invalid
 * - Structure validation: Malformed tokens must fail
 * 
 * JWT VALIDATION RULES:
 * 1. Token has 3 parts: header.payload.signature
 * 2. Signature matches (HMAC-SHA256 with secret)
 * 3. Expiration time (exp) > current time
 * 4. All required claims present (sub, iat, exp)
 * 
 * @author Chat4All Educational Project
 */
public class JwtAuthenticatorTest {
    
    private JwtAuthenticator authenticator;
    private TokenGenerator tokenGenerator;
    private static final String TEST_SECRET = "test-secret-key-for-junit";
    
    @BeforeEach
    public void setUp() {
        authenticator = new JwtAuthenticator(TEST_SECRET);
        tokenGenerator = new TokenGenerator(TEST_SECRET);
    }
    
    /**
     * Test: Validate valid token returns user ID
     * 
     * GIVEN: A freshly generated valid JWT token
     * WHEN: validateToken() is called
     * THEN: Returns the user ID from "sub" claim
     */
    @Test
    public void testValidateValidToken() {
        // Given
        String userId = "user_alice";
        String token = tokenGenerator.generateToken(userId);
        
        // When
        String validatedUserId = authenticator.validateToken(token);
        
        // Then
        assertThat(validatedUserId).isEqualTo(userId);
    }
    
    /**
     * Test: Validate expired token throws exception
     * 
     * GIVEN: A token with past expiration time
     * WHEN: validateToken() is called
     * THEN: JwtAuthenticationException is thrown with "expired" message
     * 
     * EDUCATIONAL NOTE: Simulating expired token by creating one with past exp claim.
     * In real scenario, wait 1+ hour for token to expire naturally.
     */
    @Test
    public void testValidateExpiredToken() {
        // Given - Create token generator with 0-second expiration (immediate expiry)
        TokenGenerator shortLivedGenerator = new TokenGenerator(TEST_SECRET, 0);
        String token = shortLivedGenerator.generateToken("user_bob");
        
        // When/Then
        assertThatThrownBy(() -> authenticator.validateToken(token))
            .isInstanceOf(JwtAuthenticationException.class)
            .hasMessageContaining("Token expired");
    }
    
    /**
     * Test: Validate token with invalid signature throws exception
     * 
     * GIVEN: A token signed with different secret key
     * WHEN: validateToken() is called
     * THEN: JwtAuthenticationException with "invalid signature" message
     * 
     * EDUCATIONAL NOTE: This simulates token tampering.
     * If attacker changes payload, signature won't match -> rejected.
     */
    @Test
    public void testValidateInvalidSignature() {
        // Given - Generate token with different secret
        TokenGenerator otherGenerator = new TokenGenerator("wrong-secret-key");
        String token = otherGenerator.generateToken("user_charlie");
        
        // When/Then
        assertThatThrownBy(() -> authenticator.validateToken(token))
            .isInstanceOf(JwtAuthenticationException.class)
            .hasMessageContaining("Invalid token");
    }
    
    /**
     * Test: Validate malformed token (not 3 parts) throws exception
     * 
     * GIVEN: A token with only 2 parts (missing signature)
     * WHEN: validateToken() is called
     * THEN: JwtAuthenticationException with "malformed" message
     */
    @Test
    public void testValidateMalformedToken() {
        // Given
        String malformedToken = "header.payload"; // Missing signature
        
        // When/Then
        assertThatThrownBy(() -> authenticator.validateToken(malformedToken))
            .isInstanceOf(JwtAuthenticationException.class)
            .hasMessageContaining("Malformed token");
    }
    
    /**
     * Test: Validate null token throws exception
     * 
     * GIVEN: Token is null
     * WHEN: validateToken() is called
     * THEN: IllegalArgumentException
     */
    @Test
    public void testValidateNullToken() {
        // Given
        String token = null;
        
        // When/Then
        assertThatThrownBy(() -> authenticator.validateToken(token))
            .isInstanceOf(JwtAuthenticationException.class)
            .hasMessageContaining("Token cannot be null");
    }
    
    /**
     * Test: Validate empty token throws exception
     * 
     * GIVEN: Token is empty string
     * WHEN: validateToken() is called
     * THEN: IllegalArgumentException
     */
    @Test
    public void testValidateEmptyToken() {
        // Given
        String token = "";
        
        // When/Then
        assertThatThrownBy(() -> authenticator.validateToken(token))
            .isInstanceOf(JwtAuthenticationException.class)
            .hasMessageContaining("Token cannot be null");
    }
    
    /**
     * Test: Validate token with tampered payload throws exception
     * 
     * GIVEN: A valid token with payload manually changed
     * WHEN: validateToken() is called
     * THEN: JwtAuthenticationException (signature mismatch)
     * 
     * EDUCATIONAL NOTE: This demonstrates JWT tamper-detection.
     * Changing "user_alice" to "user_admin" in payload breaks signature.
     */
    @Test
    public void testValidateTamperedPayload() {
        // Given - Generate valid token
        String validToken = tokenGenerator.generateToken("user_alice");
        String[] parts = validToken.split("\\.");
        
        // Tamper with payload: change user_alice to user_admin
        String originalPayload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String tamperedPayload = originalPayload.replace("user_alice", "user_admin");
        String tamperedPayloadBase64 = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(tamperedPayload.getBytes());
        
        // Reconstruct token with tampered payload (original signature)
        String tamperedToken = parts[0] + "." + tamperedPayloadBase64 + "." + parts[2];
        
        // When/Then
        assertThatThrownBy(() -> authenticator.validateToken(tamperedToken))
            .isInstanceOf(JwtAuthenticationException.class)
            .hasMessageContaining("Invalid token");
    }
    
    /**
     * Test: Validate token multiple times returns same user ID (idempotent)
     * 
     * GIVEN: A valid token
     * WHEN: validateToken() is called multiple times
     * THEN: Always returns same user ID (no side effects)
     */
    @Test
    public void testValidateTokenIsIdempotent() {
        // Given
        String userId = "user_dave";
        String token = tokenGenerator.generateToken(userId);
        
        // When
        String result1 = authenticator.validateToken(token);
        String result2 = authenticator.validateToken(token);
        String result3 = authenticator.validateToken(token);
        
        // Then
        assertThat(result1).isEqualTo(userId);
        assertThat(result2).isEqualTo(userId);
        assertThat(result3).isEqualTo(userId);
    }
}
