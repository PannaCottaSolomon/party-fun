package payment.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import payment.exception.UnauthorizedException;

/**
 * Verifies the party.fun access tokens (JWTs) locally. Payment never calls the service that issues them (today the
 * Authentication Service; later the combined Event/Authentication service): it only needs the shared HS256 secret.
 *
 * <p>A token is accepted when its signature matches the shared secret, it has not expired, and (only if
 * {@code auth_jwt_issuer} is set) its {@code iss} claim matches. The issuer's tokens carry {@code sub},
 * {@code email} and {@code role} ({@code PARTICIPANT}, {@code ORGANISER} or {@code ADMIN}).
 */
public final class JwtVerifier {

    /** HS256 needs at least a 256-bit key; the issuer enforces the same minimum. */
    static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final String issuer;

    public JwtVerifier(String secret, String issuer) {
        String value = secret == null ? "" : secret.trim();
        if (value.isEmpty()) {
            this.key = null;
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                // Never include the secret itself in the message.
                throw new IllegalStateException("auth_jwt_secret must be at least " + MIN_SECRET_BYTES
                        + " bytes (HS256); use the same value as the JWT issuer's AUTH_JWT_SECRET");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
        this.issuer = issuer == null || issuer.isBlank() ? null : issuer.trim();
    }

    /** False when no secret is configured: then no JWT is ever accepted. */
    public boolean enabled() {
        return key != null;
    }

    /**
     * @throws UnauthorizedException if the token is malformed, wrongly signed, expired, has the wrong issuer, or JWTs
     *                               are not enabled
     */
    public Claims verify(String token) {
        if (key == null) {
            throw new UnauthorizedException("JWT authentication is not configured on the Payment Service");
        }
        try {
            JwtParserBuilder parser = Jwts.parser().verifyWith(key);
            if (issuer != null) {
                parser.requireIssuer(issuer);
            }
            return parser.build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid or expired access token");
        }
    }
}
