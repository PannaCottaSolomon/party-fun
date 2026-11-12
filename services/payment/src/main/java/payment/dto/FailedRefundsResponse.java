package payment.dto;

import java.util.List;

/**
 * Body of {@code GET /admin/refunds/failed}.
 *
 * @param count                  number of REFUND_FAILED payments in MySQL
 * @param dlq                    the queue's counts from RabbitMQ
 * @param failedRefunds          the failed refunds, oldest failure first, each with its DLQ message count
 * @param unmatchedDlqMessages   payment ids of DLQ messages whose payment is no longer REFUND_FAILED (e.g. already
 *                               refunded). Recovering such a payment removes its stale message.
 */
public record FailedRefundsResponse(int count, DlqCountResponse dlq, List<FailedRefundView> failedRefunds,
                                    List<Integer> unmatchedDlqMessages) {

    public FailedRefundsResponse {
        failedRefunds = List.copyOf(failedRefunds);
        unmatchedDlqMessages = List.copyOf(unmatchedDlqMessages);
    }
}
