package auth.service;

import org.springframework.stereotype.Service;

import auth.cognito.CognitoGateway;
import auth.cognito.CognitoTokens;
import auth.config.FeatureFlags;
import auth.dto.AuthDto;
import auth.exception.ForbiddenException;

/**
 * Login, refresh and logout, forwarded to the Cognito user pool, for testing with API tools. The frontend calls
 * Cognito directly for the same operations; sign-up happens there too (see {@code UserService#onboard}). Also the
 * public settings the frontend needs, and confirming a sign-up without the emailed code while email verification is
 * switched off.
 */
@Service
public class AuthService {

    private final CognitoGateway cognito;
    private final FeatureFlags features;

    public AuthService(CognitoGateway cognito, FeatureFlags features) {
        this.cognito = cognito;
        this.features = features;
    }

    /** The switches the frontend follows (public, no secrets). */
    public AuthDto.ConfigResponse config() {
        return new AuthDto.ConfigResponse(features.emailVerification());
    }

    /**
     * Confirms a new account without the emailed code. Only while email verification is switched off; with it on,
     * the user must enter the code (403 here).
     */
    public void confirmWithoutCode(String username) {
        if (features.emailVerification()) {
            throw new ForbiddenException("Email verification is on: confirm the account with the code from the email");
        }
        cognito.confirmWithoutCode(username.trim());
    }

    public AuthDto.TokenResponse login(AuthDto.LoginRequest request) {
        return toResponse(cognito.login(normalize(request.getEmail()), request.getPassword()));
    }

    public AuthDto.TokenResponse refresh(String refreshToken) {
        return toResponse(cognito.refresh(refreshToken));
    }

    /**
     * Revokes the given refresh token in Cognito. Idempotent: logging out with an unknown
     * or already-revoked token is a no-op, not an error.
     */
    public void logout(String refreshToken) {
        cognito.revoke(refreshToken);
    }

    private static AuthDto.TokenResponse toResponse(CognitoTokens tokens) {
        return new AuthDto.TokenResponse(tokens.accessToken(), tokens.idToken(), tokens.refreshToken(),
                tokens.expiresInSeconds());
    }

    /** Cognito usernames and email aliases are case-insensitive; trim stray spaces from copy-paste. */
    private static String normalize(String identifier) {
        return identifier == null ? null : identifier.trim().toLowerCase();
    }
}
