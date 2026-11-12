package auth.service;

import org.junit.jupiter.api.Test;

import auth.cognito.CognitoGateway;
import auth.config.FeatureFlags;
import auth.exception.ForbiddenException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The EMAIL_VERIFICATION_ENABLED switch, as the frontend sees it. */
class AuthServiceTest {

    private final CognitoGateway cognito = mock(CognitoGateway.class);

    @Test
    void config_tellsTheFrontendWhetherTheCodeIsNeeded() {
        assertThat(new AuthService(cognito, new FeatureFlags(false)).config().emailVerificationEnabled()).isFalse();
        assertThat(new AuthService(cognito, new FeatureFlags(true)).config().emailVerificationEnabled()).isTrue();
    }

    @Test
    void withVerificationOff_aSignUpIsConfirmedWithoutTheCode() {
        new AuthService(cognito, new FeatureFlags(false)).confirmWithoutCode(" newbie ");

        verify(cognito).confirmWithoutCode("newbie");
    }

    @Test
    void withVerificationOn_confirmingWithoutTheCodeIsRefused_andCognitoIsNotAsked() {
        AuthService service = new AuthService(cognito, new FeatureFlags(true));

        assertThatThrownBy(() -> service.confirmWithoutCode("newbie"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("code from the email");
        verify(cognito, never()).confirmWithoutCode(any());
    }
}
