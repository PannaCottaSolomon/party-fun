package auth.security;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import auth.cognito.CognitoConfig;

/**
 * Cognito-shaped access tokens signed with a throw-away RSA key, plus a decoder that trusts that key and applies
 * the production validators ({@link CognitoConfig#accessTokenValidator}). Like Cognito's, the header has only
 * {@code kid} and {@code alg} (no {@code typ}).
 */
public final class TestTokens {

    public static final String ISSUER = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_testpool";
    public static final String CLIENT_ID = "test-client";

    private static final RSAKey KEY = generate("test-key");
    private static final RSAKey OTHER_KEY = generate("attacker-key");

    private TestTokens() {
    }

    /** The Cognito username in the test tokens of {@code sub}: like a real pool, not the same as the sub. */
    public static String usernameFor(UUID sub) {
        return "user-" + sub.toString().substring(0, 8);
    }

    /** A valid access token for {@code sub} in the given Cognito groups. */
    public static String access(UUID sub, String... groups) {
        return token(claims -> claims.subject(sub.toString())
                .claim("username", usernameFor(sub))
                .claim("cognito:groups", List.of(groups)));
    }

    /** A valid access token for a random user, changed by {@code customizer} (e.g. to break one claim). */
    public static String token(Consumer<JWTClaimsSet.Builder> customizer) {
        return sign(KEY, customizer);
    }

    /** Correct claims, but signed with a key that is not the pool's. */
    public static String signedWithOtherKey(UUID sub, String... groups) {
        return sign(OTHER_KEY, claims -> claims.subject(sub.toString())
                .claim("username", usernameFor(sub))
                .claim("cognito:groups", List.of(groups)));
    }

    private static String sign(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("token_use", "access")
                .claim("client_id", CLIENT_ID)
                .claim("username", "test-user")
                .claim("scope", "aws.cognito.signin.user.admin")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
        customizer.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(CognitoConfig.accessTokenValidator(ISSUER, CLIENT_ID));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces the real (JWKS-backed) decoder in web-slice tests. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class DecoderConfig {
        @Bean
        JwtDecoder testJwtDecoder() {
            return decoder();
        }
    }
}
