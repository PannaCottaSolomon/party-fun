package payment.exception;

/**
 * Stripe (or the configured gateway) could not complete a request: network error, API error,
 * missing configuration. The message is safe to show to clients; it never contains secrets. HTTP 502.
 *
 * <p>{@link #isRetryable()} tells the refund Saga whether trying again can help. Transient failures (network,
 * rate limit, Stripe 5xx) are retryable; permanent ones (invalid request, card/permission/authentication errors,
 * a refund Stripe reports as failed) are not, so they go to the DLQ without pointless retries.
 */
public class PaymentGatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final String errorCode;

    /** A transient failure: retrying may succeed. */
    public PaymentGatewayException(String message) {
        this(message, null, true, null);
    }

    /** A transient failure: retrying may succeed. */
    public PaymentGatewayException(String message, Throwable cause) {
        this(message, cause, true, null);
    }

    public PaymentGatewayException(String message, Throwable cause, boolean retryable, String errorCode) {
        super(message, cause);
        this.retryable = retryable;
        this.errorCode = errorCode;
    }

    /** A failure retrying cannot fix (e.g. invalid request, no PaymentIntent, refund declined by Stripe). */
    public static PaymentGatewayException permanent(String message, String errorCode) {
        return new PaymentGatewayException(message, null, false, errorCode);
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** Stripe's error code (e.g. "charge_already_refunded"), or null. Safe to log and alert on. */
    public String getErrorCode() {
        return errorCode;
    }
}
