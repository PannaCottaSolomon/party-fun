package auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import auth.cognito.CognitoGateway;
import auth.exception.IdentityProviderException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service must start (and pass its health checks, see ci/smoke-test.sh) without any Cognito settings, with
 * the real gateway: the bootstrap is skipped and Cognito calls fail fast with 503 instead of reaching AWS.
 */
@SpringBootTest(properties = {"auth.cognito.user-pool-id=", "auth.cognito.client-id="})
@ActiveProfiles("test")
class StartsWithoutCognitoTest {

    @Autowired
    private CognitoGateway cognito;

    @Test
    void contextLoads_andCognitoCallsFailFast() {
        assertThat(cognito.isConfigured()).isFalse();
        assertThatThrownBy(() -> cognito.login("pat@example.com", "Password123!"))
                .isInstanceOf(IdentityProviderException.class);
    }
}
