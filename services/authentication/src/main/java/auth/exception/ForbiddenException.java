package auth.exception;

/** A valid, authenticated caller that is not allowed to perform this action (HTTP 403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
