package payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import payment.entity.PaymentStatus;

/**
 * Outcome of refunding one payment.
 *
 * @param attempts Stripe refund calls made for this request (0 when nothing needed doing)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResultResponse(
        Integer paymentId,
        Outcome outcome,
        PaymentStatus paymentStatus,
        String stripeRefundId,
        int attempts,
        String message) {

    public enum Outcome {
        /** Stripe refunded the payment during this request. */
        REFUNDED,
        /** It was already refunded earlier; nothing was sent to Stripe. */
        ALREADY_REFUNDED,
        /**
         * Stripe accepted the refund but has not completed it yet: the payment stays REFUND_PENDING until the Stripe
         * webhook confirms it (then REFUNDED + notify.refund) or reports it failed (REFUND_FAILED + DLQ + alert).
         */
        PENDING,
        /** Every attempt failed: the payment is REFUND_FAILED and the request went to the DLQ. */
        FAILED,
        /** The payment was never paid (PENDING/FAILED), so there is nothing to refund. */
        SKIPPED
    }
}
