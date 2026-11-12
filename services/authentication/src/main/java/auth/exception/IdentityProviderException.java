package auth.exception;

/**
 * Cognito is not configured, could not be reached or failed unexpectedly (HTTP 503). The cause is logged, never
 * returned to the client.
 */
public class IdentityProviderException extends RuntimeException {
    public IdentityProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
