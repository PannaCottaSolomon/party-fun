package payment.messaging;

import payment.entity.Booking;
import payment.entity.Payment;
import payment.stripe.Money;

/**
 * What a completed refund needs for the participant's {@code notify.refund} email. Built when a payment becomes
 * REFUNDED, from the payment and its booking.
 *
 * <p>The participant is named by user id, never by email address: the Notification Service looks up the address in
 * the user directory it keeps from the Authentication Service's {@code user.*} messages.
 *
 * @param registrationId the participant-facing booking reference: the Event Service's booking id when known,
 *                       otherwise this service's booking id
 * @param userId         the participant's user id (the booking's userId)
 * @param eventTitle     event title from the booking (may be null for a booking created without one)
 */
public record RefundNotification(
        Integer paymentId,
        String registrationId,
        Integer userId,
        String eventTitle,
        long amountCents,
        String currency,
        String refundId) {

    public static RefundNotification of(Payment payment) {
        Booking booking = payment.getBooking();
        String registrationId = booking.getEventBookingId() != null
                ? booking.getEventBookingId() : String.valueOf(booking.getBookingId());
        return new RefundNotification(payment.getPaymentId(), registrationId, booking.getUserId(),
                booking.getEventTitle(), Money.toCents(payment.getAmount()), "SGD", payment.getStripeRefundId());
    }

    /** The Notification Service rejects notify.refund without these, so we never send an incomplete one. */
    public boolean hasRequiredFields() {
        return userId != null && eventTitle != null && !eventTitle.isBlank();
    }
}
