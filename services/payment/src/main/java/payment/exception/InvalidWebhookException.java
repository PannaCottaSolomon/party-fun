package payment.exception;

/** A Stripe webhook had a bad signature or an unreadable body. HTTP 400. */
public class InvalidWebhookException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidWebhookException(String message) {
        super(message);
    }

    public InvalidWebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}
