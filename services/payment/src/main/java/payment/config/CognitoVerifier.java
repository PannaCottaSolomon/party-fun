package payment.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.InvalidKeyException;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.PublicJwk;
import payment.exception.UnauthorizedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies Amazon Cognito access tokens: what the frontend sends through the API Gateway, whose JWT authorizer
 * checks them too. Payment checks them again itself (its port is reachable without the gateway): the RS256 signature
 * against the user pool's public keys (its JWKS, fetched on first use and again for a key id it doesn't know, at most
 * once a minute), the issuer (the user pool), {@code token_use = access}, and the expiry. An Admin is a user in the
 * pool's {@code admin} group ({@code cognito:groups}).
 *
 * <p>Off when no user pool is configured (local runs and tests): then only the party.fun JWT and the keys count.
 */
public final class CognitoVerifier {

    /** The Cognito group whose users are Admins (Cognito group names are lowercase in this user pool). */
    static final String ADMIN_GROUP = "admin";

    private static final Duration REFETCH_INTERVAL = Duration.ofMinutes(1);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String issuer;
    private final Function<String, String> jwksFetcher;  // JWKS URL -> its JSON
    private final Clock clock;
    private volatile Map<String, Key> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    /** Production: the user pool's issuer from its region and id, keys fetched over HTTPS. Empty pool id = off. */
    public CognitoVerifier(String region, String userPoolId) {
        this(issuer(region, userPoolId), CognitoVerifier::download, Clock.systemUTC());
    }

    /** Tests: a given issuer and key source. */
    CognitoVerifier(String issuer, Function<String, String> jwksFetcher, Clock clock) {
        this.issuer = issuer;
        this.jwksFetcher = jwksFetcher;
        this.clock = clock;
    }

    public boolean enabled() {
        return !issuer.isEmpty();
    }

    /** Whether the token says it comes from this user pool (read without verifying it, to choose the verifier). */
    public boolean issued(String token) {
        if (!enabled()) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        try {
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return issuer.equals(payload.path("iss").asString(""));
        } catch (RuntimeException notJson) {
            return false;
        }
    }

    /** The verified claims of a Cognito access token from this user pool, or 401. */
    public Claims verify(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            return key(header.getKeyId());
                        }
                    })
                    .requireIssuer(issuer)
                    .clock(() -> java.util.Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid or expired access token");
        }
        if (!"access".equals(claims.get("token_use"))) {
            throw new UnauthorizedException("Invalid or expired access token");  // e.g. an ID token
        }
        return claims;
    }

    /** Whether verified claims belong to a user in the {@code admin} group. */
    public static boolean isAdmin(Claims claims) {
        return claims.get("cognito:groups") instanceof Collection<?> groups
                && groups.stream().anyMatch(group -> ADMIN_GROUP.equals(String.valueOf(group).toLowerCase(Locale.ROOT)));
    }

    /** The public key with this id, fetching the JWKS again if it is unknown (rotation), at most once a minute. */
    private Key key(String keyId) {
        Key key = keyId == null ? null : keys.get(keyId);
        if (key == null && Duration.between(fetchedAt, clock.instant()).compareTo(REFETCH_INTERVAL) >= 0) {
            refresh();
            key = keyId == null ? null : keys.get(keyId);
        }
        if (key == null) {
            throw new InvalidKeyException("Unknown signing key");
        }
        return key;
    }

    private synchronized void refresh() {
        fetchedAt = clock.instant();
        Map<String, Key> fetched = new HashMap<>();
        for (Jwk<?> jwk : Jwks.setParser().build().parse(jwksFetcher.apply(issuer + "/.well-known/jwks.json"))) {
            if (jwk instanceof PublicJwk<?> publicJwk && jwk.getId() != null) {
                fetched.put(jwk.getId(), publicJwk.toKey());
            }
        }
        keys = Map.copyOf(fetched);
    }

    private static String issuer(String region, String userPoolId) {
        String pool = userPoolId == null ? "" : userPoolId.trim();
        if (pool.isEmpty() || region == null || region.isBlank()) {
            return "";
        }
        return "https://cognito-idp." + region.trim() + ".amazonaws.com/" + pool;
    }

    private static String download(String url) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new UnauthorizedException("Invalid or expired access token");
            }
            return response.body();
        } catch (java.io.IOException e) {
            throw new UnauthorizedException("Invalid or expired access token");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnauthorizedException("Invalid or expired access token");
        }
    }
}
