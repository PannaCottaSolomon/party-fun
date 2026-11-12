package auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import auth.dto.AuthDto;
import auth.exception.ForbiddenException;
import auth.exception.IdentityProviderException;
import auth.exception.InvalidCredentialsException;
import auth.exception.InvalidTokenException;
import auth.security.CognitoTokenVerifier;
import auth.service.AuthService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer tests for the /auth test-login endpoints. */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    // Needed by WebSecurityConfig, which the web slice loads; /auth/** is not intercepted.
    @MockitoBean
    private CognitoTokenVerifier tokenVerifier;

    // ---- register is gone: sign-up happens in Cognito through the frontend ----

    @Test
    void register_isNotOffered() throws Exception {
        String payload = """
                {"email":"alice@example.com","password":"Password123!","fullName":"Alice","role":"PARTICIPANT"}""";

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isNotFound());
    }

    // ---- feature switches and confirming without the code ----

    @Test
    void config_isPublic() throws Exception {
        when(authService.config()).thenReturn(new AuthDto.ConfigResponse(false));

        mockMvc.perform(get("/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerificationEnabled").value(false));
    }

    @Test
    void confirmSignUp_returns204() throws Exception {
        mockMvc.perform(post("/auth/confirm-sign-up").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\"}"))
                .andExpect(status().isNoContent());
        verify(authService).confirmWithoutCode("newbie");
    }

    @Test
    void confirmSignUp_whileVerificationIsOn_returns403() throws Exception {
        doThrow(new ForbiddenException("Email verification is on: confirm the account with the code from the email"))
                .when(authService).confirmWithoutCode("newbie");

        mockMvc.perform(post("/auth/confirm-sign-up").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmSignUp_withoutUsername_returns400() throws Exception {
        mockMvc.perform(post("/auth/confirm-sign-up").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---- login ----

    @Test
    void login_returnsTokens() throws Exception {
        when(authService.login(any())).thenReturn(
                new AuthDto.TokenResponse("access.jwt.token", "id.jwt.token", "refresh-token-value", 3600));

        String payload = """
                {"email":"alice@example.com","password":"password123"}""";

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt.token"))
                .andExpect(jsonPath("$.idToken").value("id.jwt.token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token-value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600));
    }

    @Test
    void login_acceptsACognitoUsername() throws Exception {
        when(authService.login(any())).thenReturn(new AuthDto.TokenResponse("a", "i", "r", 3600));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin\",\"password\":\"Example-Pass1!\"}"))
                .andExpect(status().isOk());
        verify(authService).login(argThat(request -> "admin".equals(request.getEmail())));
    }

    @Test
    void login_missingPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        String payload = """
                {"email":"alice@example.com","password":"wrongpassword"}""";

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_cognitoUnavailable_returns503_withoutDetails() throws Exception {
        when(authService.login(any())).thenThrow(new IdentityProviderException("Cognito login failed",
                new RuntimeException("Unable to execute HTTP request: cognito-idp... (secret detail)")));

        String payload = """
                {"email":"alice@example.com","password":"password123"}""";

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Cognito login failed"));
    }

    // ---- refresh ----

    @Test
    void refresh_returnsNewTokens() throws Exception {
        when(authService.refresh(any())).thenReturn(
                new AuthDto.TokenResponse("new.access.jwt", "new.id.jwt", "old-refresh-value", 3600));

        String payload = """
                {"refreshToken":"old-refresh-value"}""";

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("old-refresh-value"))
                .andExpect(jsonPath("$.idToken").value("new.id.jwt"));
    }

    @Test
    void refresh_invalidToken_returns401() throws Exception {
        when(authService.refresh(any())).thenThrow(new InvalidTokenException("Refresh token is expired or revoked"));

        String payload = """
                {"refreshToken":"revoked-token"}""";

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_blankToken_returns400() throws Exception {
        String payload = """
                {"refreshToken":""}""";

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
    }

    // ---- logout ----

    @Test
    void logout_returns204_andRevokes() throws Exception {
        String payload = """
                {"refreshToken":"some-refresh-value"}""";

        mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isNoContent());

        verify(authService).logout("some-refresh-value");
    }
}
