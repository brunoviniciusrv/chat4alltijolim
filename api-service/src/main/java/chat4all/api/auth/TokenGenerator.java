package chat4all.api.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.util.Date;

/**
 * TokenGenerator - Gerador de tokens JWT para autenticação
 * 
 * Esta classe implementa a geração de JSON Web Tokens (JWT) usando
 * o algoritmo HMAC-SHA256 (HS256). 
 * 
 * CONCEITOS EDUCACIONAIS:
 * 
 * 1. JWT (JSON Web Token):
 *    - Estrutura: header.payload.signature (3 partes separadas por ponto)
 *    - header: {"alg":"HS256","typ":"JWT"} - Define o algoritmo usado
 *    - payload: {"sub":"user_alice","iat":1700000000,"exp":1700003600} - Claims do usuário
 *    - signature: HMAC-SHA256(base64(header) + "." + base64(payload), secret) - Garante integridade
 * 
 * 2. HMAC-SHA256:
 *    - Hash-based Message Authentication Code usando SHA-256
 *    - Combina hash criptográfico com chave secreta (secret)
 *    - IMPORTANTE: Não é encriptação! O payload é visível (apenas Base64, qualquer um pode decodificar)
 *    - Garante apenas INTEGRIDADE: se alguém alterar o payload, a assinatura não baterá
 * 
 * 3. Base64URL Encoding:
 *    - Variante do Base64 segura para URLs
 *    - Substitui + por - e / por _
 *    - Remove padding (=) no final
 * 
 * 4. Claims JWT:
 *    - sub (subject): Identifica o usuário (no nosso caso, o user_id)
 *    - iat (issued at): Timestamp Unix de quando o token foi criado
 *    - exp (expiration): Timestamp Unix de quando o token expira
 * 
 * SEGURANÇA:
 * - Secret deve ter no mínimo 256 bits (32 bytes)
 * - Secret NUNCA deve ser commitado no Git
 * - Secret deve ser diferente em dev/staging/production
 * - Tokens expirados devem ser rejeitados (previne replay attacks)
 * 
 * USO:
 * ```java
 * TokenGenerator generator = new TokenGenerator("my-super-secret-key");
 * String token = generator.generateToken("user_alice");
 * // token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyX2FsaWNlIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwMDM2MDB9.signature"
 * ```
 */
public class TokenGenerator {
    
    /**
     * Secret usado para assinar os tokens JWT.
     * Em produção, deve vir de variável de ambiente (System.getenv("JWT_SECRET")).
     */
    private final String secret;
    
    /**
     * Tempo de expiração do token em segundos.
     * Padrão: 3600 segundos = 1 hora.
     */
    private final int expirationSeconds;
    
    /**
     * Construtor padrão com expiração de 1 hora.
     * 
     * @param secret Chave secreta para assinar os tokens (mínimo 32 bytes recomendado)
     * @throws IllegalArgumentException se secret for null ou vazio
     */
    public TokenGenerator(String secret) {
        this(secret, 3600); // Padrão: 1 hora
    }
    
    /**
     * Construtor com expiração customizada.
     * Útil para testes (expiração de 0 segundos para testar tokens expirados).
     * 
     * @param secret Chave secreta para assinar os tokens
     * @param expirationSeconds Tempo de expiração em segundos (0 = expira imediatamente)
     * @throws IllegalArgumentException se secret for null/vazio ou expirationSeconds < 0
     */
    public TokenGenerator(String secret, int expirationSeconds) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret cannot be null or empty");
        }
        if (expirationSeconds < 0) {
            throw new IllegalArgumentException("Expiration seconds cannot be negative");
        }
        this.secret = secret;
        this.expirationSeconds = expirationSeconds;
    }
    
    /**
     * Gera um token JWT para o usuário especificado.
     * 
     * FLUXO:
     * 1. Valida se userId não é null ou vazio
     * 2. Cria timestamp atual (iat - issued at)
     * 3. Calcula timestamp de expiração (exp = iat + expirationSeconds)
     * 4. Cria JWT com algorithm HMAC256, subject (userId), iat, exp
     * 5. Assina o token com o secret
     * 6. Retorna string no formato: header.payload.signature
     * 
     * ESTRUTURA DO TOKEN GERADO:
     * ```
     * eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyX2FsaWNlIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwMDM2MDB9.1a2b3c4d5e...
     * └─────────── header ──────────────┘ └───────────────────── payload ─────────────────────────┘ └── signature ──┘
     * ```
     * 
     * DECODIFICANDO (para fins educacionais - qualquer um pode fazer isso):
     * ```bash
     * echo "eyJzdWIiOiJ1c2VyX2FsaWNlIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwMDM2MDB9" | base64 -d
     * # Resultado: {"sub":"user_alice","iat":1700000000,"exp":1700003600}
     * ```
     * 
     * POR QUE CADA GERAÇÃO É DIFERENTE?
     * Mesmo passando o mesmo userId, o timestamp (iat) muda a cada segundo,
     * então os tokens serão diferentes. Isso é desejável para rastreabilidade.
     * 
     * @param userId Identificador único do usuário (será o claim "sub" do JWT)
     * @return Token JWT assinado no formato header.payload.signature
     * @throws IllegalArgumentException se userId for null ou vazio
     */
    public String generateToken(String userId) {
        // Validação de entrada
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        
        // Timestamp atual (iat - issued at)
        Date issuedAt = new Date();
        
        // Timestamp de expiração (exp = iat + expirationSeconds)
        Date expiresAt = new Date(issuedAt.getTime() + (expirationSeconds * 1000L));
        
        // Criação do algoritmo HMAC-SHA256 com o secret
        Algorithm algorithm = Algorithm.HMAC256(secret);
        
        // Criação e assinatura do JWT
        String token = JWT.create()
                .withSubject(userId)           // Claim "sub": identifica o usuário
                .withIssuedAt(issuedAt)        // Claim "iat": quando foi criado
                .withExpiresAt(expiresAt)      // Claim "exp": quando expira
                .sign(algorithm);              // Assina com HMAC-SHA256
        
        return token;
    }
}
