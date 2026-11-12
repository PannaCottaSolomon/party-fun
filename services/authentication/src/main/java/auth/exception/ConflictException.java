package auth.exception;

/** The request clashes with the account's current state, e.g. its profile already exists (HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
