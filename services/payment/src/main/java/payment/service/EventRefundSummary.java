package payment.service;

import java.util.List;

import payment.dto.RefundResultResponse;
import payment.dto.RefundResultResponse.Outcome;

/**
 * What handling one event.cancelled did: one result per eligible payment, each refunded individually.
 *
 * @param refunded refunds Stripe confirmed (including ones that were already refunded)
 * @param pending  refunds Stripe accepted but has not completed yet (finished by webhook)
 * @param failed   refunds that still failed after their attempts: each is REFUND_FAILED and in the refund DLQ
 */
public record EventRefundSummary(Integer eventId, List<RefundResultResponse> results, int refunded, int pending,
                                 int failed) {

    public EventRefundSummary {
        results = List.copyOf(results);
    }

    static EventRefundSummary of(Integer eventId, List<RefundResultResponse> results) {
        int refunded = 0;
        int pending = 0;
        int failed = 0;
        for (RefundResultResponse result : results) {
            Outcome outcome = result.outcome();
            if (outcome == Outcome.REFUNDED || outcome == Outcome.ALREADY_REFUNDED) {
                refunded++;
            } else if (outcome == Outcome.PENDING) {
                pending++;
            } else if (outcome == Outcome.FAILED) {
                failed++;
            }
        }
        return new EventRefundSummary(eventId, results, refunded, pending, failed);
    }
}
