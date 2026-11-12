package auth.exception;

public class EmailAlreadyExistsException extends ConflictException {
    public EmailAlreadyExistsException(String email) {
        super("An account with this email already exists: " + email);
    }
}
