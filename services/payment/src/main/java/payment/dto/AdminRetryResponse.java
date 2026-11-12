package payment.dto;

import payment.dto.RefundResultResponse.Outcome;
import payment.entity.PaymentStatus;

/**
 * Body of {@code POST /admin/refunds/{paymentId}/retry}: what the recovery did.
 *
 * @param outcome            REFUNDED (Stripe refunded it now, or already had), ALREADY_REFUNDED (it was REFUNDED
 *                           before), PENDING (Stripe accepted a refund that is not final yet) or FAILED (still
 *                           failing; it stays REFUND_FAILED and in the DLQ)
 * @param dlqMessagesFound   DLQ messages for this payment found and held during the recovery
 * @param dlqMessagesRemoved how many of them were acknowledged and removed (0 when the recovery failed)
 * @param remainingInDlq     messages waiting in payments.refunds.dlq afterwards (all payments; exact, over AMQP)
 */
public record AdminRetryResponse(
        Integer paymentId,
        Outcome outcome,
        PaymentStatus paymentStatus,
        String stripeRefundId,
        int dlqMessagesFound,
        int dlqMessagesRemoved,
        long remainingInDlq,
        String message) {
}
