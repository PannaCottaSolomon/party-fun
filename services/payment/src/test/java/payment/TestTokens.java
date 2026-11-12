package payment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** HS256 access tokens shaped like the JWT issuer's (sub, email, role), for tests. */
public final class TestTokens {

    public static final String SECRET = "test-jwt-secret-that-is-at-least-32-bytes-long";

    private TestTokens() {
    }

    public static String token(String role) {
        return token(SECRET, role, Instant.now().plusSeconds(900), null);
    }

    public static String token(String secret, String role, Instant expiry, String issuer) {
        JwtBuilder builder = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", role.toLowerCase() + "@partyfun.sg")
                .claim("role", role)
                .issuedAt(Date.from(Instant.now().minusSeconds(60)))
                .expiration(Date.from(expiry))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        return builder.compact();
    }

    public static String bearer(String role) {
        return "Bearer " + token(role);
    }
}
