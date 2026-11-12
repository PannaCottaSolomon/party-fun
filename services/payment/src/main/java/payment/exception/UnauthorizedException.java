package payment.exception;

/** The request lacks valid credentials (e.g. a missing or wrong {@code X-Admin-Api-Key}). HTTP 401. */
public class UnauthorizedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String message) {
        super(message);
    }
}
