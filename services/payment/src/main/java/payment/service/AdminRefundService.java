package payment.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.stereotype.Service;

import payment.dto.AdminRetryResponse;
import payment.dto.DlqCountResponse;
import payment.dto.FailedRefundView;
import payment.dto.FailedRefundsResponse;
import payment.dto.RefundResultResponse;
import payment.dto.RefundResultResponse.Outcome;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.PaymentGatewayException;
import payment.messaging.RefundDeadLetterQueue;
import payment.messaging.RefundDeadLetterQueue.DlqEntry;
import payment.messaging.RefundDeadLetterQueue.DlqStats;
import payment.messaging.RefundDeadLetterQueue.Settlement;
import payment.repository.PaymentRepository;
import payment.service.RefundService.RecoveryClaim;
import payment.service.RefundService.RefundAttemptResult;
import payment.stripe.GatewayRefundResult;
import payment.stripe.PaymentGateway;

/**
 * The Admin's view of failed refunds and their recovery (Admin → frontend → Payment Service → Stripe; the Admin
 * never talks to RabbitMQ). MySQL is authoritative for payment and refund status; {@code payments.refunds.dlq}
 * holds one message per failed refund, which a successful recovery acknowledges (removes).
 *
 * <p>Recovery of one payment ({@link #retry}):
 * <ol>
 *   <li>Claim it in MySQL under a row lock: REFUND_FAILED → REFUND_PENDING. A second Admin gets 409.</li>
 *   <li>Hold its DLQ message(s) unacknowledged; unrelated messages are put back at once.</li>
 *   <li>Check Stripe: a refund that already succeeded is recorded (no new refund); one that is still pending is left
 *       to its webhook. Otherwise refund again, in a new round with a new Stripe idempotency key.</li>
 *   <li>Refunded or pending at Stripe: acknowledge (remove) the held message(s) and, once refunded, notify.refund.
 *       Still failing: REFUND_FAILED with the new reason, and the held message is put back unchanged (no duplicate);
 *       if the payment had no DLQ message, one is published so the failure stays represented.</li>
 * </ol>
 */
@Service
public class AdminRefundService {

    private static final Logger log = LoggerFactory.getLogger(AdminRefundService.class);
    /** How many DLQ messages the dashboard looks at to correlate them with MySQL. */
    static final int PEEK_LIMIT = 500;

    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final PaymentGateway gateway;
    private final RefundDeadLetterQueue deadLetterQueue;

    public AdminRefundService(PaymentRepository paymentRepository, RefundService refundService,
                              PaymentGateway gateway, RefundDeadLetterQueue deadLetterQueue) {
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.gateway = gateway;
        this.deadLetterQueue = deadLetterQueue;
    }

    /** {@code GET /admin/refunds/dlq/count}. */
    public DlqCountResponse deadLetterCount() {
        return toResponse(deadLetterQueue.stats());
    }

    /** {@code GET /admin/refunds/failed}: REFUND_FAILED payments from MySQL, correlated with the DLQ messages. */
    public FailedRefundsResponse failedRefunds() {
        List<Payment> failed = paymentRepository.findWithBookingByStatus(PaymentStatus.REFUND_FAILED);
        Map<Integer, Integer> messagesPerPayment = new HashMap<>();
        for (DlqEntry entry : deadLetterQueue.peek(PEEK_LIMIT)) {
            if (entry.paymentId() != null) {
                messagesPerPayment.merge(entry.paymentId(), 1, Integer::sum);
            }
        }
        Set<Integer> failedIds = new HashSet<>();
        List<FailedRefundView> views = new ArrayList<>();
        for (Payment payment : failed) {
            failedIds.add(payment.getPaymentId());
            views.add(FailedRefundView.of(payment, messagesPerPayment.getOrDefault(payment.getPaymentId(), 0)));
        }
        List<Integer> unmatched = messagesPerPayment.keySet().stream()
                .filter(id -> !failedIds.contains(id))
                .sorted()
                .toList();
        return new FailedRefundsResponse(views.size(), deadLetterCount(), views, unmatched);
    }

    /** {@code POST /admin/refunds/{paymentId}/retry}: Admin-controlled recovery of one failed refund. */
    public AdminRetryResponse retry(Integer paymentId) {
        RecoveryClaim claim = refundService.claimForRecovery(paymentId); // 404 / 409 before touching the DLQ
        String requestId = "admin-retry-" + paymentId + "-" + UUID.randomUUID();
        Recovery recovery;
        try {
            recovery = deadLetterQueue.settle(paymentId, held -> recover(paymentId, claim, held, requestId));
        } catch (AmqpException e) {
            // Could not reach the DLQ: give the claim back so the payment stays REFUND_FAILED for a later retry.
            if (!claim.alreadyRefunded()) {
                refundService.releaseRecoveryClaim(paymentId, "Recovery could not reach RabbitMQ: " + e.getMessage());
            }
            throw e;
        }
        // Exact and immediate (AMQP); the management API's statistics would still count the message just removed.
        long remaining = deadLetterQueue.readyCount();
        RefundResultResponse result = recovery.result();
        log.info("Admin recovery of payment {}: {} ({} DLQ message(s) held, {} removed; {} left in the DLQ)",
                paymentId, result.outcome(), recovery.held(), recovery.removed(), remaining);
        return new AdminRetryResponse(paymentId, result.outcome(), result.paymentStatus(), result.stripeRefundId(),
                recovery.held(), recovery.removed(), remaining, result.message());
    }

    /** Runs while the payment's DLQ message(s) are held; decides whether they are removed. */
    private Settlement<Recovery> recover(Integer paymentId, RecoveryClaim claim, int held, String requestId) {
        if (claim.alreadyRefunded()) {
            RefundResultResponse result = new RefundResultResponse(paymentId, Outcome.ALREADY_REFUNDED,
                    PaymentStatus.REFUNDED, claim.stripeRefundId(), 0,
                    "Payment was already refunded; its stale DLQ message(s) were removed");
            return Settlement.remove(new Recovery(result, held, held));
        }

        List<GatewayRefundResult> atStripe;
        try {
            atStripe = gateway.listRefunds(claim.paymentIntentId());
        } catch (PaymentGatewayException e) {
            String reason = "Could not check the refund's state at Stripe: " + e.getMessage();
            refundService.releaseRecoveryClaim(paymentId, reason);
            ensureInDeadLetterQueue(paymentId, held);
            return Settlement.keep(new Recovery(new RefundResultResponse(paymentId, Outcome.FAILED,
                    PaymentStatus.REFUND_FAILED, null, 0, reason), held, 0));
        }

        Optional<GatewayRefundResult> succeeded = atStripe.stream().filter(GatewayRefundResult::succeeded).findFirst();
        if (succeeded.isPresent()) {
            RefundResultResponse result = refundService.reconcileSucceededRefund(paymentId, succeeded.get().refundId());
            return Settlement.remove(new Recovery(result, held, held));
        }
        Optional<GatewayRefundResult> pending = atStripe.stream().filter(GatewayRefundResult::inProgress).findFirst();
        if (pending.isPresent()) {
            // Stripe's webhook will finish it; if Stripe fails it, the webhook publishes a new DLQ message.
            RefundResultResponse result = refundService.reconcilePendingRefund(paymentId, pending.get().refundId());
            return Settlement.remove(new Recovery(result, held, held));
        }

        RefundAttemptResult attempt = refundService.refundForRecovery(paymentId, requestId);
        RefundResultResponse result = attempt.result();
        if (result.outcome() == Outcome.FAILED) {
            if (held == 0 && attempt.failure() != null) {
                refundService.sendToDeadLetterQueue(attempt.failure()); // represent the failure individually
            }
            return Settlement.keep(new Recovery(result, held, 0));
        }
        return Settlement.remove(new Recovery(result, held, held));
    }

    /** A REFUND_FAILED payment without any DLQ message gets one, so every outstanding failure is in the queue. */
    private void ensureInDeadLetterQueue(Integer paymentId, int held) {
        if (held == 0) {
            refundService.sendToDeadLetterQueue(refundService.currentFailure(paymentId, RefundTrigger.ADMIN_RETRY));
        }
    }

    private static DlqCountResponse toResponse(DlqStats stats) {
        return new DlqCountResponse(stats.queue(), stats.ready(), stats.unacknowledged(), stats.total(),
                stats.source());
    }

    /** What one recovery did. */
    record Recovery(RefundResultResponse result, int held, int removed) {
    }
}
