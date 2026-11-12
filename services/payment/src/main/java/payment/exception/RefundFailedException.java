package payment.exception;

/** A refund still failed after every retry. The payment is REFUND_FAILED and the request is in the DLQ. HTTP 502. */
public class RefundFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RefundFailedException(String message) {
        super(message);
    }
}
