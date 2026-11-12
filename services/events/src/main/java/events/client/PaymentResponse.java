package events.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A payment as returned by the payment service ({@code POST /payments}, {@code GET /payments/booking/{id}}).
 *
 * <p>Only the fields this service reads are mapped; the rest of the payment service's {@code Payment} object
 * (Stripe ids, timestamps, ...) is ignored.
 *
 * @param paymentId     id of the payment in the payment service (stored as {@code Booking.paymentReference})
 * @param bookingId     the payment-side booking it belongs to
 * @param status        {@code PAID}, {@code FAILED}, {@code PENDING}, {@code REFUND_PENDING}, {@code REFUNDED} or
 *                      {@code REFUND_FAILED}
 * @param failureReason why the card was declined (only on a {@code FAILED} payment returned by {@code POST /payments})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResponse(Integer paymentId, Integer bookingId, String status, String failureReason) {

    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";

    public boolean isPaid() {
        return PAID.equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return FAILED.equalsIgnoreCase(status);
    }
}
