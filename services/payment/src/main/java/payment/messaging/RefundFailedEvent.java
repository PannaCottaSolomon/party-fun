package payment.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A refund that still failed after its Stripe attempts. Used to build the {@code notify.admin_alert} (failure reason
 * and attempt count) and the individual message Payment publishes to the refund DLQ ({@link FailedRefundMessage}).
 * The Admin recovers it with {@code POST /admin/refunds/{paymentId}/retry}.
 *
 * @param retryCount     number of Stripe refund attempts made (the configured maximum, 3, unless the error was
 *                       permanent, in which case retrying stopped early)
 * @param errorCode      Stripe's error code when known (e.g. "charge_already_refunded"); never card data or keys
 * @param retryable      whether the last error was transient (network, rate limit, Stripe 5xx)
 * @param stripeRefundId the Stripe refund that failed, when there was one (e.g. reported failed by webhook)
 * @param trigger        EVENT_CANCELLED, MANUAL (REST refund endpoint), ADMIN_RETRY, RECOVERY or WEBHOOK
 */
public record RefundFailedEvent(
        String eventType,
        Integer paymentId,
        Integer bookingId,
        Integer eventId,
        Integer userId,
        BigDecimal amount,
        long amountCents,
        String currency,
        String stripePaymentIntentId,
        String stripeRefundId,
        String failureReason,
        int retryCount,
        String errorCode,
        boolean retryable,
        String trigger,
        String requestId,
        Instant timestamp) {

    public static final String TYPE = "refund.failed";
}
