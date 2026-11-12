package events.client;

// The payment service answered with a 4xx: a definite refusal (bad request, unknown or non-PENDING booking, ...).
// Nothing was charged by that call. The message is the payment service's own error message.
public class PaymentRejectedException extends RuntimeException {

    private final int statusCode;

    public PaymentRejectedException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
