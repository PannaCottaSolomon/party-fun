package payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import payment.TestTokens;
import payment.config.AuthConfig.AccessInterceptor;
import payment.exception.ForbiddenException;
import payment.exception.UnauthorizedException;

/** Admin Cognito login, Admin JWT, operator key and service key, every combination, without a Spring context. */
class AuthConfigTest {

    private static final String ADMIN_KEY = "admin-operator-secret";
    private static final String SERVICE_KEY = "service-secret";
    private static final JwtVerifier JWT = new JwtVerifier(TestTokens.SECRET, "");
    private static final CognitoVerifier COGNITO_OFF = new CognitoVerifier("", "");
    private static final CognitoTokens POOL = new CognitoTokens();
    private static final CognitoVerifier COGNITO = new CognitoVerifier(CognitoTokens.ISSUER, url -> POOL.jwks(),
            Clock.systemUTC());

    @Test
    void anAdminsCognitoLoginIsAcceptedOnTheAdminAndRefundEndpoints() {
        String bearer = "Bearer " + POOL.accessToken(List.of("admin"));

        assertThat(call(new AccessInterceptor(JWT, COGNITO, ADMIN_KEY, SERVICE_KEY, false), "Authorization", bearer))
                .isTrue();
        assertThat(call(new AccessInterceptor(JWT, COGNITO, ADMIN_KEY, SERVICE_KEY, true), "Authorization", bearer))
                .isTrue();
    }

    @Test
    void aCognitoLoginOutsideTheAdminGroupIsForbidden() {
        String bearer = "Bearer " + POOL.accessToken(List.of("participant"));

        assertThatThrownBy(() -> call(new AccessInterceptor(JWT, COGNITO, ADMIN_KEY, SERVICE_KEY, false),
                "Authorization", bearer))
                .isInstanceOf(ForbiddenException.class).hasMessage("This endpoint requires the ADMIN role");
    }

    @Test
    void aForgedCognitoLoginIsUnauthorizedAndPartyfunTokensStillWork() {
        String forged = "Bearer " + new CognitoTokens().accessToken(List.of("admin"));
        AccessInterceptor both = new AccessInterceptor(JWT, COGNITO, ADMIN_KEY, SERVICE_KEY, false);

        assertThatThrownBy(() -> call(both, "Authorization", forged)).isInstanceOf(UnauthorizedException.class);
        assertThat(call(both, "Authorization", TestTokens.bearer("ADMIN"))).isTrue();
    }

    @Test
    void withOnlyCognitoConfiguredTheAdminEndpointsAreOpenToAdminsNotDisabled() {
        AccessInterceptor cognitoOnly = new AccessInterceptor(new JwtVerifier("", ""), COGNITO, "", "", false);

        assertThat(call(cognitoOnly, "Authorization", "Bearer " + POOL.accessToken(List.of("admin")))).isTrue();
        assertThatThrownBy(() -> call(cognitoOnly, null, null)).isInstanceOf(UnauthorizedException.class);
    }

    private static AccessInterceptor adminOnly() {
        return new AccessInterceptor(JWT, COGNITO_OFF, ADMIN_KEY, SERVICE_KEY, false);
    }

    private static AccessInterceptor refund() {
        return new AccessInterceptor(JWT, COGNITO_OFF, ADMIN_KEY, SERVICE_KEY, true);
    }

    private static boolean call(AccessInterceptor interceptor, String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/refunds/1/retry");
        if (header != null) {
            request.addHeader(header, value);
        }
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void adminJwtIsAccepted() {
        assertThat(call(adminOnly(), "Authorization", TestTokens.bearer("ADMIN"))).isTrue();
        assertThat(call(refund(), "Authorization", TestTokens.bearer("ADMIN"))).isTrue();
    }

    @Test
    void validTokenWithAnotherRoleIsForbidden() {
        for (String role : new String[] {"PARTICIPANT", "ORGANISER"}) {
            assertThatThrownBy(() -> call(adminOnly(), "Authorization", TestTokens.bearer(role)))
                    .isInstanceOf(ForbiddenException.class).hasMessage("This endpoint requires the ADMIN role");
        }
    }

    @Test
    void forgedExpiredOrMalformedTokensAreUnauthorized() {
        String forged = TestTokens.token("another-secret-that-is-also-32-bytes-long!!", "ADMIN",
                Instant.now().plusSeconds(600), null);
        String expired = TestTokens.token(TestTokens.SECRET, "ADMIN", Instant.now().minusSeconds(5), null);

        for (String token : new String[] {forged, expired, "not.a.jwt", ""}) {
            assertThatThrownBy(() -> call(adminOnly(), "Authorization", "Bearer " + token))
                    .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid or expired access token");
        }
    }

    @Test
    void issuerIsCheckedOnlyWhenConfigured() {
        JwtVerifier withIssuer = new JwtVerifier(TestTokens.SECRET, "partyfun-events-auth");
        String right = TestTokens.token(TestTokens.SECRET, "ADMIN", Instant.now().plusSeconds(600),
                "partyfun-events-auth");
        String none = TestTokens.token("ADMIN");

        assertThat(withIssuer.verify(right).get("role")).isEqualTo("ADMIN");
        assertThatThrownBy(() -> withIssuer.verify(none)).isInstanceOf(UnauthorizedException.class);
        assertThat(JWT.verify(right).get("role")).isEqualTo("ADMIN"); // no issuer configured: not checked
    }

    @Test
    void operatorKeyIsTheAdminFallback() {
        assertThat(call(adminOnly(), AuthConfig.ADMIN_HEADER, ADMIN_KEY)).isTrue();
        assertThatThrownBy(() -> call(adminOnly(), AuthConfig.ADMIN_HEADER, "wrong"))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> call(adminOnly(), null, null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void serviceKeyOnlyWorksOnTheRefundEndpoint() {
        assertThat(call(refund(), AuthConfig.SERVICE_HEADER, SERVICE_KEY)).isTrue();
        assertThatThrownBy(() -> call(adminOnly(), AuthConfig.SERVICE_HEADER, SERVICE_KEY))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> call(refund(), AuthConfig.SERVICE_HEADER, ADMIN_KEY))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void withoutAJwtSecretTokensAreIgnoredAndOnlyKeysWork() {
        AccessInterceptor keysOnly = new AccessInterceptor(new JwtVerifier("", ""), COGNITO_OFF, ADMIN_KEY, "", false);

        assertThatThrownBy(() -> call(keysOnly, "Authorization", TestTokens.bearer("ADMIN")))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(call(keysOnly, AuthConfig.ADMIN_HEADER, ADMIN_KEY)).isTrue();
    }

    @Test
    void endpointsWithNoCredentialConfiguredAreDisabledNotOpen() {
        JwtVerifier off = new JwtVerifier("", "");

        assertThatThrownBy(() -> call(new AccessInterceptor(off, COGNITO_OFF, "", "", false), null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Admin endpoints are disabled: none of auth_jwt_secret, COGNITO_USER_POOL_ID or"
                        + " payment_admin_api_key is configured");
        assertThatThrownBy(() -> call(new AccessInterceptor(off, COGNITO_OFF, "", "", true), null, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void corsPreflightNeedsNoCredentials() {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/admin/refunds/failed");

        assertThat(adminOnly().preHandle(preflight, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void configurationIsValidatedAtStartup() {
        assertThatThrownBy(() -> new AuthConfig("", "", "", "", "same", " same "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payment_admin_api_key and payment_service_api_key must be different");
        assertThatThrownBy(() -> new JwtVerifier("too-short", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes")
                .hasMessageNotContaining("too-short");
    }
}
