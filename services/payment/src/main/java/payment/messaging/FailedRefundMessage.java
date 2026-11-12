package payment.messaging;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One individually failed refund, as kept in {@code payments.refunds.dlq} until an Admin recovers it.
 *
 * <p>Payment publishes it itself to {@code payments.refund.dlx} (routing key {@code payments.refunds.dlq}) after a
 * payment's refund attempts are exhausted: application-managed routing, one message per failed payment. MySQL stays
 * the authoritative record (the payment is REFUND_FAILED with the same reason and attempt count); this message is
 * the work item the Admin dashboard counts and the recovery acknowledges.
 *
 * <p>Readers only rely on {@code paymentId}, so messages written by older versions of the service (which used the
 * shape {@code {"paymentId": ..., "amount": ..., "trigger": ...}}) are still recognised.
 *
 * @param refundAmountCents refund amount in cents (SGD)
 * @param retryCount        Stripe refund attempts made in total (1 initial attempt + retries); null in messages
 *                          written by older versions
 * @param trigger           what started the refund: EVENT_CANCELLED, MANUAL, ADMIN_RETRY, RECOVERY or WEBHOOK
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FailedRefundMessage(
        @JsonAlias("payment_id") Integer paymentId,
        Integer bookingId,
        Integer eventId,
        Integer userId,
        @JsonAlias("amountCents") Long refundAmountCents,
        String currency,
        String stripePaymentIntentId,
        String stripeRefundId,
        Integer retryCount,
        String failureReason,
        String errorCode,
        String trigger,
        @JsonAlias("requestedAt") Instant failedAt) {

    public static FailedRefundMessage from(RefundFailedEvent failure) {
        return new FailedRefundMessage(failure.paymentId(), failure.bookingId(), failure.eventId(), failure.userId(),
                failure.amountCents(), failure.currency(), failure.stripePaymentIntentId(), failure.stripeRefundId(),
                failure.retryCount(), failure.failureReason(), failure.errorCode(), failure.trigger(),
                failure.timestamp());
    }
}
