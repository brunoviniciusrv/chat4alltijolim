package chat4all.api.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * JwtAuthenticator - Validador de tokens JWT
 * 
 * Esta classe valida tokens JWT gerados pelo TokenGenerator,
 * verificando assinatura, expiração e integridade.
 * 
 * CONCEITOS EDUCACIONAIS:
 * 
 * 1. VERIFICAÇÃO DE ASSINATURA:
 *    - Recalcula HMAC-SHA256(header + payload, secret)
 *    - Compara com a assinatura recebida no token
 *    - Se não bater = token foi adulterado ou secret errado
 * 
 * 2. VERIFICAÇÃO DE EXPIRAÇÃO:
 *    - Compara claim "exp" com timestamp atual
 *    - Se exp < agora = token expirado (rejeitado)
 *    - Previne replay attacks (alguém reusar token antigo)
 * 
 * 3. VERIFICAÇÃO DE INTEGRIDADE:
 *    - Qualquer alteração no payload quebra a assinatura
 *    - Exemplo: mudar "sub":"alice" para "sub":"bob" invalida o token
 *    - Isso porque HMAC(header+payload_modificado) ≠ signature_original
 * 
 * 4. ATAQUES COMUNS PREVENIDOS:
 *    - Token Tampering: Modificar payload (assinatura não bate)
 *    - Replay Attack: Reusar token expirado (exp check)
 *    - None Algorithm Attack: Remover assinatura (verificador rejeita)
 *    - Secret Brute Force: Secret fraco seria adivinhável (use 256+ bits)
 * 
 * 5. ATAQUES NÃO PREVENIDOS (limitações do JWT):
 *    - Token Theft: Se alguém rouba o token antes de expirar, pode usar
 *      → Solução: HTTPS obrigatório + short expiration time
 *    - Secret Leak: Se secret vazar, todos os tokens ficam comprometidos
 *      → Solução: Rotacionar secrets periodicamente
 * 
 * FLUXO DE VALIDAÇÃO:
 * ```
 * Token recebido: "eyJ...header...eyJ...payload...signature"
 *                           ↓
 *           [1] Decodifica header e payload (Base64URL)
 *                           ↓
 *           [2] Recalcula signature = HMAC(header+payload, secret)
 *                           ↓
 *           [3] Compara signature_recalculada com signature_recebida
 *                           ↓
 *           [4] Verifica se exp > now (não expirou)
 *                           ↓
 *           [5] Retorna user_id (claim "sub")
 * ```
 * 
 * USO:
 * ```java
 * JwtAuthenticator authenticator = new JwtAuthenticator("my-super-secret-key");
 * 
 * try {
 *     String userId = authenticator.validateToken("eyJ...");
 *     // userId = "user_alice" → Token válido!
 * } catch (JwtAuthenticationException e) {
 *     // Token inválido/expirado → Retornar 401 Unauthorized
 * }
 * ```
 */
public class JwtAuthenticator {
    
    /**
     * Secret usado para verificar a assinatura dos tokens.
     * DEVE SER O MESMO secret usado no TokenGenerator.
     */
    private final String secret;
    
    /**
     * Verificador JWT pré-configurado (reutilizável).
     * Criado uma vez no construtor para performance.
     */
    private final JWTVerifier verifier;
    
    /**
     * Construtor do JwtAuthenticator.
     * 
     * Cria um verificador JWT configurado com:
     * - Algorithm HMAC256 (mesmo usado na geração)
     * - Verificação automática de expiração (exp claim)
     * 
     * @param secret Chave secreta (DEVE SER A MESMA do TokenGenerator)
     * @throws IllegalArgumentException se secret for null ou vazio
     */
    public JwtAuthenticator(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret cannot be null or empty");
        }
        this.secret = secret;
        
        // Cria o algoritmo HMAC-SHA256
        Algorithm algorithm = Algorithm.HMAC256(secret);
        
        // Cria o verificador JWT (reutilizável)
        this.verifier = JWT.require(algorithm)
                .build(); // Verifica automaticamente exp (expiração)
    }
    
    /**
     * Valida um token JWT e retorna o user ID.
     * 
     * FLUXO DETALHADO:
     * 
     * 1. VALIDAÇÃO DE ENTRADA:
     *    - Rejeita tokens null ou vazios
     *    - Rejeita tokens sem 3 partes (header.payload.signature)
     * 
     * 2. VERIFICAÇÃO DE ASSINATURA:
     *    - Decodifica header e payload (Base64URL)
     *    - Recalcula: expected_signature = HMAC256(header + payload, secret)
     *    - Compara: expected_signature == received_signature?
     *    - Se diferente → JWTVerificationException (token adulterado ou secret errado)
     * 
     * 3. VERIFICAÇÃO DE EXPIRAÇÃO:
     *    - Extrai claim "exp" (timestamp Unix)
     *    - Compara: exp > Date.now()?
     *    - Se expirado → TokenExpiredException
     * 
     * 4. EXTRAÇÃO DE USER ID:
     *    - Extrai claim "sub" (subject) do payload
     *    - Retorna user_id
     * 
     * EXEMPLOS DE TOKENS INVÁLIDOS:
     * 
     * Token expirado:
     * ```
     * {"sub":"alice","iat":1700000000,"exp":1700000001}
     *                                       ↑
     *                          Expirou 1 segundo após criação
     * ```
     * 
     * Token com assinatura inválida (payload adulterado):
     * ```
     * Original: eyJ...header...eyJzdWIiOiJhbGljZSJ9.signature_alice
     *                                     ↓ Alguém mudou para "bob"
     * Adulterado: eyJ...header...eyJzdWIiOiJib2IifQ.signature_alice
     *                                                 ↑
     *                                   Signature não bate mais!
     * ```
     * 
     * Token malformado (faltando partes):
     * ```
     * "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9"  ← Só 2 partes (faltando signature)
     * "eyJhbGciOiJIUzI1NiJ9"                        ← Só 1 parte (faltando payload e signature)
     * ""                                             ← Vazio
     * ```
     * 
     * POR QUE VALIDAÇÃO É IDEMPOTENTE?
     * Validar o mesmo token múltiplas vezes retorna sempre o mesmo user_id,
     * desde que o token não tenha expirado entre as validações.
     * Isso é importante para cache de autenticação.
     * 
     * @param token Token JWT a ser validado (formato: header.payload.signature)
     * @return User ID extraído do claim "sub"
     * @throws JwtAuthenticationException se token for inválido, expirado ou malformado
     */
    public String validateToken(String token) throws JwtAuthenticationException {
        // 1. Validação de entrada
        if (token == null || token.trim().isEmpty()) {
            throw new JwtAuthenticationException("Token cannot be null or empty");
        }
        
        // Verifica estrutura básica (deve ter 3 partes: header.payload.signature)
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtAuthenticationException("Malformed token: expected 3 parts (header.payload.signature), got " + parts.length);
        }
        
        try {
            // 2. Verificação de assinatura e expiração
            // O verifier automaticamente:
            // - Decodifica header e payload
            // - Recalcula HMAC-SHA256(header + payload, secret)
            // - Compara com signature recebida
            // - Verifica se exp > now
            DecodedJWT jwt = verifier.verify(token);
            
            // 3. Extração do user ID (claim "sub")
            String userId = jwt.getSubject();
            
            if (userId == null || userId.trim().isEmpty()) {
                throw new JwtAuthenticationException("Token missing 'sub' claim");
            }
            
            return userId;
            
        } catch (TokenExpiredException e) {
            // Token expirado (exp < now)
            throw new JwtAuthenticationException("Token expired: " + e.getMessage());
            
        } catch (JWTVerificationException e) {
            // Assinatura inválida, token adulterado, ou outro erro de verificação
            throw new JwtAuthenticationException("Invalid token: " + e.getMessage());
        }
    }
}
