package payment.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import payment.config.PaymentProperties;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.exception.NotFoundException;
import payment.repository.PaymentRepository;

/**
 * Resumes refunds that were abandoned mid-way: claimed (REFUND_PENDING) but never sent to Stripe successfully and
 * unchanged for {@code payment.recovery.stale-after} (10 min), e.g. because the service stopped during a REST refund.
 *
 * <p>Event cancellations are already crash-safe on their own (the event.cancelled message is only acknowledged once
 * every refund was handled, so RabbitMQ redelivers it); this job covers the rest. Resuming is safe: it reuses the
 * round's Stripe idempotency key, so Stripe returns the original refund instead of creating a second one. A refund
 * that still fails is reported like any other (REFUND_FAILED, admin alert, one message in the refund DLQ).
 */
@Component
public class RefundRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(RefundRecoveryJob.class);

    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final Clock clock;
    private final PaymentProperties.Recovery settings;

    public RefundRecoveryJob(PaymentRepository paymentRepository, RefundService refundService, Clock clock,
                             PaymentProperties properties) {
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.clock = clock;
        this.settings = properties.recovery();
    }

    @Scheduled(initialDelayString = "${payment.recovery.interval:PT5M}",
            fixedDelayString = "${payment.recovery.interval:PT5M}")
    public void resumeAbandonedRefunds() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(settings.staleAfter());
        List<Integer> abandoned = paymentRepository.findAbandonedRefunds(PaymentStatus.REFUND_PENDING, cutoff);
        for (Integer paymentId : abandoned) {
            log.warn("Payment {} has been REFUND_PENDING without a Stripe refund since before {}; resuming its refund",
                    paymentId, cutoff);
            try {
                refundService.refundAndReport(paymentId, RefundTrigger.RECOVERY, "recovery-" + paymentId);
            } catch (NotFoundException | InvalidStateException e) {
                log.info("Payment {} no longer needs recovery: {}", paymentId, e.getMessage());
            }
        }
    }
}
