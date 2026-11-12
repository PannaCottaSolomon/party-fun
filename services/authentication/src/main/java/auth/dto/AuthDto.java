package auth.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthDto {

    // ---- Requests ----

    /** Test login. {@code email} may also be the Cognito username, as on the frontend's login page. */
    public static class LoginRequest {
        @NotBlank
        private String email;

        @NotBlank
        private String password;

        public LoginRequest() {}

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /** Confirm a new account without the emailed code (only while email verification is switched off). */
    public static class ConfirmSignUpRequest {
        @NotBlank
        private String username;

        public ConfirmSignUpRequest() {}

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
    }

    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;

        public RefreshRequest() {}

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }

    public static class LogoutRequest {
        @NotBlank
        private String refreshToken;

        public LogoutRequest() {}

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }

    // ---- Responses ----

    /** {@code GET /auth/config}: the feature switches the frontend follows. */
    public record ConfigResponse(boolean emailVerificationEnabled) {}

    /**
     * Cognito's tokens. Send {@code accessToken} as {@code Authorization: Bearer} to every service; the
     * {@code idToken} carries the user's email for the frontend and is not accepted as a bearer token.
     */
    public static class TokenResponse {
        private String accessToken;
        private String idToken;
        private String refreshToken;
        private String tokenType = "Bearer";
        private long expiresInSeconds;

        public TokenResponse() {}

        public TokenResponse(String accessToken, String idToken, String refreshToken, long expiresInSeconds) {
            this.accessToken = accessToken;
            this.idToken = idToken;
            this.refreshToken = refreshToken;
            this.expiresInSeconds = expiresInSeconds;
        }

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getIdToken() { return idToken; }
        public void setIdToken(String idToken) { this.idToken = idToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public long getExpiresInSeconds() { return expiresInSeconds; }
        public void setExpiresInSeconds(long expiresInSeconds) { this.expiresInSeconds = expiresInSeconds; }
    }
}
