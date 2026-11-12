package events.client;

// No definite answer from the payment service (timeout, connection refused, 5xx, unreadable response).
// The outcome is unknown: a charge may or may not have gone through. Trying again is safe because the payment
// service refuses a second payment for a booking that already has a PENDING or PAID one (409), which
// BookingPaymentService then reconciles. EventExceptionHandler maps it to 503.
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message) {
        super(message);
    }

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
