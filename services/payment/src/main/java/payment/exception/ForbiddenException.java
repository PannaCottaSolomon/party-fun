package payment.exception;

/** The operation is not available at all (e.g. Admin endpoints while no Admin API key is configured). HTTP 403. */
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
