package payment.config;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import io.jsonwebtoken.Jwts;

/** A stand-in Cognito user pool for tests: an RSA key pair, its JWKS, and access tokens signed with it. */
final class CognitoTokens {

    static final String ISSUER = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_TestPool";
    static final String KEY_ID = "test-key-1";

    final KeyPair keys = Jwts.SIG.RS256.keyPair().build();

    /** The pool's JWKS, as Cognito serves it at {issuer}/.well-known/jwks.json. */
    String jwks() {
        RSAPublicKey key = (RSAPublicKey) keys.getPublic();
        return """
                {"keys": [{"kty": "RSA", "alg": "RS256", "use": "sig", "kid": "%s", "n": "%s", "e": "%s"}]}
                """.formatted(KEY_ID, base64Url(key.getModulus()), base64Url(key.getPublicExponent()));
    }

    String accessToken(List<String> groups) {
        return token(ISSUER, "access", groups, Instant.now().plusSeconds(600), KEY_ID);
    }

    String token(String issuer, String tokenUse, List<String> groups, Instant expiry, String keyId) {
        return Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(issuer)
                .subject("sub-" + String.join("-", groups))
                .claim("token_use", tokenUse)
                .claim("cognito:groups", groups)
                .issuedAt(Date.from(Instant.now().minusSeconds(60)))
                .expiration(Date.from(expiry))
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);  // unsigned big-endian, as JWK requires
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
