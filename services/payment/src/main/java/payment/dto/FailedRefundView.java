package payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.stripe.Money;

/**
 * One individually failed refund on the Admin dashboard. The payment details come from MySQL (authoritative);
 * {@code dlqMessages} says how many messages for it are waiting in {@code payments.refunds.dlq}. No card data or
 * Stripe secrets are included.
 *
 * @param refundAttempts Stripe refund attempts made in the failed round (1 initial attempt + retries)
 * @param dlqMessages    0 means MySQL has the failure but the DLQ has no message for it (e.g. the publish failed);
 *                       recovering it works the same
 */
public record FailedRefundView(
        Integer paymentId,
        Integer bookingId,
        Integer eventId,
        Integer userId,
        BigDecimal amount,
        long amountCents,
        String currency,
        PaymentStatus status,
        String failureReason,
        int refundAttempts,
        LocalDateTime failedAt,
        String stripePaymentIntentId,
        String stripeRefundId,
        int dlqMessages) {

    public static FailedRefundView of(Payment payment, int dlqMessages) {
        return new FailedRefundView(payment.getPaymentId(), payment.getBooking().getBookingId(),
                payment.getBooking().getEventId(), payment.getBooking().getUserId(), payment.getAmount(),
                Money.toCents(payment.getAmount()), "SGD", payment.getStatus(), payment.getRefundFailureReason(),
                payment.getRefundAttempts(), payment.getRefundFailedAt(), payment.getStripePaymentIntentId(),
                payment.getStripeRefundId(), dlqMessages);
    }
}
