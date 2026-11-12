package payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import payment.TestTokens;
import payment.exception.UnauthorizedException;

/** Cognito access tokens: signature, issuer, token type, expiry, the admin group, and fetching the pool's keys. */
class CognitoVerifierTest {

    private final CognitoTokens pool = new CognitoTokens();
    private final AtomicInteger fetches = new AtomicInteger();

    private CognitoVerifier verifier(Clock clock) {
        return new CognitoVerifier(CognitoTokens.ISSUER, url -> {
            assertThat(url).isEqualTo(CognitoTokens.ISSUER + "/.well-known/jwks.json");
            fetches.incrementAndGet();
            return pool.jwks();
        }, clock);
    }

    private CognitoVerifier verifier() {
        return verifier(Clock.systemUTC());
    }

    @Test
    void anAdminsAccessTokenIsVerifiedAndRecognisedAsAdmin() {
        String token = pool.accessToken(List.of("admin"));
        CognitoVerifier cognito = verifier();

        assertThat(cognito.issued(token)).isTrue();
        assertThat(CognitoVerifier.isAdmin(cognito.verify(token))).isTrue();
    }

    @Test
    void aUserOutsideTheAdminGroupIsNotAnAdmin() {
        CognitoVerifier cognito = verifier();

        assertThat(CognitoVerifier.isAdmin(cognito.verify(pool.accessToken(List.of("participant"))))).isFalse();
        assertThat(CognitoVerifier.isAdmin(cognito.verify(pool.accessToken(List.of())))).isFalse();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsUnauthorized() {
        String forged = new CognitoTokens().accessToken(List.of("admin"));  // same key id, different key

        assertThatThrownBy(() -> verifier().verify(forged))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid or expired access token");
    }

    @Test
    void anExpiredTokenIsUnauthorized() {
        String expired = pool.token(CognitoTokens.ISSUER, "access", List.of("admin"), Instant.now().minusSeconds(5),
                CognitoTokens.KEY_ID);

        assertThatThrownBy(() -> verifier().verify(expired)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anIdTokenIsNotAnAccessToken() {
        String idToken = pool.token(CognitoTokens.ISSUER, "id", List.of("admin"), Instant.now().plusSeconds(600),
                CognitoTokens.KEY_ID);

        assertThatThrownBy(() -> verifier().verify(idToken)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void tokensFromAnotherIssuerAreNotThisPools() {
        String otherPool = pool.token("https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_Other",
                "access", List.of("admin"), Instant.now().plusSeconds(600), CognitoTokens.KEY_ID);
        CognitoVerifier cognito = verifier();

        assertThat(cognito.issued(otherPool)).isFalse();
        assertThat(cognito.issued(TestTokens.token("ADMIN"))).isFalse();  // a party.fun token
        assertThat(cognito.issued("not.a.jwt")).isFalse();
        assertThatThrownBy(() -> cognito.verify(otherPool)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void theKeysAreFetchedOnceAndAgainForAnUnknownKeyIdAtMostOnceAMinute() {
        Instant now = Instant.now();
        CognitoVerifier cognito = verifier(Clock.fixed(now, ZoneOffset.UTC));
        String unknownKey = pool.token(CognitoTokens.ISSUER, "access", List.of("admin"), now.plusSeconds(600),
                "rotated-key");

        cognito.verify(pool.accessToken(List.of("admin")));
        cognito.verify(pool.accessToken(List.of("admin")));
        assertThat(fetches).hasValue(1);

        assertThatThrownBy(() -> cognito.verify(unknownKey)).isInstanceOf(UnauthorizedException.class);
        assertThat(fetches).hasValue(1);  // fetched less than a minute ago: not again

        CognitoVerifier later = verifier(Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC));
        later.verify(pool.accessToken(List.of("admin")));
        assertThatThrownBy(() -> later.verify(unknownKey)).isInstanceOf(UnauthorizedException.class);
        assertThat(fetches).hasValue(2);  // the new verifier's first fetch; its unknown key waits a minute too
    }

    @Test
    void withoutAUserPoolCognitoTokensAreNotAccepted() {
        CognitoVerifier off = new CognitoVerifier("ap-southeast-1", "");

        assertThat(off.enabled()).isFalse();
        assertThat(off.issued(pool.accessToken(List.of("admin")))).isFalse();
        assertThat(new CognitoVerifier("ap-southeast-1", "ap-southeast-1_TestPool").enabled()).isTrue();
    }
}
