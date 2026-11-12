package auth.cognito;

/** The tokens Cognito returns from a login or refresh. */
public record CognitoTokens(String accessToken, String idToken, String refreshToken, long expiresInSeconds) {
}
