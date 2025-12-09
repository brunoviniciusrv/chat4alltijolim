package chat4all.api.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.*;

/**
 * TokenGeneratorTest - Unit Tests for JWT Token Generation
 * 
 * EDUCATIONAL PURPOSE: Test-First Development (TDD)
 * ==================
 * This test class is written BEFORE TokenGenerator.java exists.
 * Following TDD RED-GREEN-REFACTOR cycle:
 * 
 * 1. RED: Write tests -> They FAIL (TokenGenerator doesn't exist yet)
 * 2. GREEN: Write minimal code to make tests PASS
 * 3. REFACTOR: Clean up code while keeping tests green
 * 
 * WHY TEST JWT TOKEN GENERATION?
 * - Security critical: Wrong JWT = unauthorized access
 * - Token structure: Must have header.payload.signature
 * - Claims validation: user_id, expiration time
 * - Algorithm: HMAC-SHA256 (HS256)
 * 
 * @author Chat4All Educational Project
 */
public class TokenGeneratorTest {
    
    private TokenGenerator tokenGenerator;
    private static final String TEST_SECRET = "test-secret-key-for-junit";
    
    @BeforeEach
    public void setUp() {
        // Initialize token generator with test secret
        tokenGenerator = new TokenGenerator(TEST_SECRET);
    }
    
    /**
     * Test: Generate token with valid user ID
     * 
     * GIVEN: A valid user ID "user_alice"
     * WHEN: generateToken() is called
     * THEN: A JWT token string is returned (not null, not empty)
     */
    @Test
    public void testGenerateTokenWithUserId() {
        // Given
        String userId = "user_alice";
        
        // When
        String token = tokenGenerator.generateToken(userId);
        
        // Then
        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();
        
        // JWT structure: header.payload.signature (3 parts separated by dots)
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
    }
    
    /**
     * Test: Token contains required claims
     * 
     * GIVEN: A user ID "user_bob"
     * WHEN: Token is generated
     * THEN: Token payload contains:
     *       - "sub" claim (subject = user_id)
     *       - "iat" claim (issued at timestamp)
     *       - "exp" claim (expiration timestamp)
     * 
     * EDUCATIONAL NOTE: We decode the payload (Base64) to verify claims.
     * In production, use java-jwt library's JWT.decode() method.
     */
    @Test
    public void testTokenContainsClaims() {
        // Given
        String userId = "user_bob";
        
        // When
        String token = tokenGenerator.generateToken(userId);
        
        // Then - Extract payload (middle part of JWT)
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        
        String payload = parts[1];
        
        // Decode Base64URL payload
        byte[] decodedBytes = java.util.Base64.getUrlDecoder().decode(payload);
        String decodedPayload = new String(decodedBytes);
        
        // Verify claims exist in JSON payload
        assertThat(decodedPayload).contains("\"sub\":\"user_bob\"");
        assertThat(decodedPayload).contains("\"iat\":");
        assertThat(decodedPayload).contains("\"exp\":");
    }
    
    /**
     * Test: Token expiration is set correctly
     * 
     * GIVEN: Token expiration configured to 1 hour (3600 seconds)
     * WHEN: Token is generated
     * THEN: exp claim = iat claim + 3600 seconds
     * 
     * EDUCATIONAL NOTE: JWT expiration prevents token reuse.
     * After 1 hour, token is invalid even if signature is correct.
     */
    @Test
    public void testTokenExpiration() {
        // Given
        String userId = "user_charlie";
        long beforeGeneration = System.currentTimeMillis() / 1000; // Unix timestamp in seconds
        
        // When
        String token = tokenGenerator.generateToken(userId);
        long afterGeneration = System.currentTimeMillis() / 1000;
        
        // Then - Extract and decode payload
        String[] parts = token.split("\\.");
        byte[] decodedBytes = java.util.Base64.getUrlDecoder().decode(parts[1]);
        String payload = new String(decodedBytes);
        
        // Extract iat and exp claims (simple JSON parsing for test)
        long iat = extractTimestampClaim(payload, "iat");
        long exp = extractTimestampClaim(payload, "exp");
        
        // Verify iat is within generation time window
        assertThat(iat).isBetween(beforeGeneration, afterGeneration);
        
        // Verify exp = iat + 3600 seconds (1 hour)
        assertThat(exp).isEqualTo(iat + 3600);
    }
    
    /**
     * Test: Generate token with null user ID throws exception
     * 
     * GIVEN: user ID is null
     * WHEN: generateToken() is called
     * THEN: IllegalArgumentException is thrown
     */
    @Test
    public void testGenerateTokenWithNullUserIdThrowsException() {
        // Given
        String userId = null;
        
        // When/Then
        assertThatThrownBy(() -> tokenGenerator.generateToken(userId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("user ID cannot be null");
    }
    
    /**
     * Test: Generate token with empty user ID throws exception
     * 
     * GIVEN: user ID is empty string
     * WHEN: generateToken() is called
     * THEN: IllegalArgumentException is thrown
     */
    @Test
    public void testGenerateTokenWithEmptyUserIdThrowsException() {
        // Given
        String userId = "";
        
        // When/Then
        assertThatThrownBy(() -> tokenGenerator.generateToken(userId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("user ID cannot be empty");
    }
    
    /**
     * Test: Same user ID generates different tokens (nonce/timestamp)
     * 
     * GIVEN: Same user ID "user_dave"
     * WHEN: generateToken() is called twice
     * THEN: Two different tokens are returned (different timestamps)
     * 
     * EDUCATIONAL NOTE: JWT includes "iat" (issued at) timestamp,
     * so even same user generates unique tokens each time.
     */
    @Test
    public void testSameUserIdGeneratesDifferentTokens() throws InterruptedException {
        // Given
        String userId = "user_dave";
        
        // When
        String token1 = tokenGenerator.generateToken(userId);
        Thread.sleep(1100); // Wait 1.1 seconds to ensure different timestamp
        String token2 = tokenGenerator.generateToken(userId);
        
        // Then
        assertThat(token1).isNotEqualTo(token2);
    }
    
    // ==================
    // HELPER METHODS
    // ==================
    
    /**
     * Extract timestamp claim from JWT payload JSON
     * 
     * EXAMPLE: extractTimestampClaim("{\"iat\":1700000000}", "iat") -> 1700000000
     * 
     * @param payload Decoded JWT payload (JSON string)
     * @param claimName Claim name ("iat" or "exp")
     * @return Unix timestamp (seconds since epoch)
     */
    private long extractTimestampClaim(String payload, String claimName) {
        String pattern = "\"" + claimName + "\":";
        int startIndex = payload.indexOf(pattern);
        if (startIndex == -1) {
            throw new AssertionError("Claim '" + claimName + "' not found in payload: " + payload);
        }
        
        startIndex += pattern.length();
        int endIndex = payload.indexOf(",", startIndex);
        if (endIndex == -1) {
            endIndex = payload.indexOf("}", startIndex); // Last claim
        }
        
        String timestampStr = payload.substring(startIndex, endIndex).trim();
        return Long.parseLong(timestampStr);
    }
}
