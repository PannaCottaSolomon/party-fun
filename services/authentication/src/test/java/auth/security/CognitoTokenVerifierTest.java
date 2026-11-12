package auth.security;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import auth.entity.UserAccount;
import auth.exception.InvalidTokenException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the /user endpoints accept as a Cognito access token, without a Spring context. */
class CognitoTokenVerifierTest {

    private final CognitoTokenVerifier verifier = new CognitoTokenVerifier(TestTokens.decoder());
    private final UUID sub = UUID.randomUUID();

    @Test
    void validAccessToken_givesSubUsernameAndGroupRoles() {
        AuthenticatedUser user = verifier.verify(TestTokens.access(sub, "organiser"));

        assertThat(user.id()).isEqualTo(sub);
        assertThat(user.username()).isEqualTo(TestTokens.usernameFor(sub));
        assertThat(user.roles()).containsExactly(UserAccount.Role.ORGANISER);
        assertThat(user.isAdmin()).isFalse();
    }

    @Test
    void tokenWithoutAUsername_isRejected() {
        String token = TestTokens.token(c -> c.subject(sub.toString()).claim("username", null));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Access token has no username");
    }

    @Test
    void adminGroup_isAdmin_andGroupCaseDoesNotMatter() {
        AuthenticatedUser user = verifier.verify(TestTokens.access(sub, "Admin"));

        assertThat(user.isAdmin()).isTrue();
    }

    @Test
    void unknownOrMissingGroups_grantNoRole() {
        assertThat(verifier.verify(TestTokens.access(sub, "beta-testers")).roles()).isEmpty();
        assertThat(verifier.verify(TestTokens.token(c -> c.subject(sub.toString()))).roles()).isEmpty();
    }

    @Test
    void expiredToken_isRejected() {
        Instant past = Instant.now().minusSeconds(7200);
        String token = TestTokens.token(c -> c.issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(3600))));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenFromAnotherUserPool_isRejected() {
        String token = TestTokens.token(c -> c.issuer("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_other"));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void idToken_isNotAcceptedAsBearerToken() {
        String token = TestTokens.token(c -> c.claim("token_use", "id").audience(TestTokens.CLIENT_ID));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenForAnotherAppClient_isRejected() {
        String token = TestTokens.token(c -> c.claim("client_id", "some-other-client"));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        assertThatThrownBy(() -> verifier.verify(TestTokens.signedWithOtherKey(sub, "admin")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void garbage_isRejected() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void subjectThatIsNotAUuid_isRejected() {
        String token = TestTokens.token(c -> c.subject("google_1234").claim("cognito:groups", List.of("admin")));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("subject");
    }
}
