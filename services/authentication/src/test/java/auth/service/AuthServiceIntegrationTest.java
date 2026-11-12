package auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import auth.cognito.CognitoGateway;
import auth.cognito.CognitoTokens;
import auth.dto.AuthDto;
import auth.exception.InvalidCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The test-login endpoints' service with Cognito mocked: Cognito's tokens are passed through unchanged. The event
 * publisher is mocked so the test needs no running RabbitMQ.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @MockitoBean
    private CognitoGateway cognito;

    @MockitoBean
    private UserEventPublisher eventPublisher;

    private AuthDto.LoginRequest loginRequest(String email, String password) {
        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    @Test
    void login_returnsCognitosTokens() {
        when(cognito.login("pat@example.com", "Password123!"))
                .thenReturn(new CognitoTokens("access.jwt", "id.jwt", "refresh-value", 3600));

        AuthDto.TokenResponse tokens = authService.login(loginRequest("pat@example.com", "Password123!"));

        assertThat(tokens.getAccessToken()).isEqualTo("access.jwt");
        assertThat(tokens.getIdToken()).isEqualTo("id.jwt");
        assertThat(tokens.getRefreshToken()).isEqualTo("refresh-value");
        assertThat(tokens.getTokenType()).isEqualTo("Bearer");
        assertThat(tokens.getExpiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void login_normalisesTheIdentifier() {
        when(cognito.login("mixed@example.com", "Password123!"))
                .thenReturn(new CognitoTokens("access", "id", "refresh", 3600));

        AuthDto.TokenResponse tokens = authService.login(loginRequest(" MIXED@example.com ", "Password123!"));

        assertThat(tokens.getAccessToken()).isEqualTo("access");
    }

    @Test
    void login_wrongPassword_isRejected() {
        when(cognito.login(anyString(), anyString())).thenThrow(new InvalidCredentialsException());

        assertThatThrownBy(() -> authService.login(loginRequest("pat@example.com", "nope")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_and_logout_goToCognito() {
        when(cognito.refresh("refresh-value"))
                .thenReturn(new CognitoTokens("new-access", "new-id", "refresh-value", 3600));

        assertThat(authService.refresh("refresh-value").getAccessToken()).isEqualTo("new-access");

        authService.logout("refresh-value");
        verify(cognito).revoke("refresh-value");
    }
}
