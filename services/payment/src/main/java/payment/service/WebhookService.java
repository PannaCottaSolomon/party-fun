package payment.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.messaging.PaymentEventPublisher;
import payment.messaging.RefundFailedEvent;
import payment.messaging.RefundNotification;
import payment.repository.PaymentRepository;
import payment.stripe.GatewayWebhookEvent;
import payment.stripe.PaymentGateway;

/**
 * Applies Stripe webhook events to local payments.
 *
 * <p>Idempotent by state: an event only acts if the payment is still in the state the event moves it
 * out of. A repeated (or late, or out-of-order) event finds the payment already updated and is acknowledged
 * without changes. Stripe retries any non-2xx response, so events we don't care about still get 200.
 *
 * <ul>
 *   <li>{@code payment_intent.succeeded} / {@code payment_intent.payment_failed}: PENDING → PAID / FAILED. A
 *       success after a failure (the customer tried another card on the same PaymentIntent in Stripe.js) is
 *       FAILED → PAID: Stripe took the money, so the payment must say so.</li>
 *   <li>Refund confirmed ({@code refund.*}, {@code charge.refund.updated} with status succeeded, or
 *       {@code charge.refunded}): REFUND_PENDING/REFUND_FAILED → REFUNDED, then {@code notify.refund}.</li>
 *   <li>Refund failed ({@code refund.failed}, or status failed/canceled): REFUND_PENDING → REFUND_FAILED, then
 *       {@code notify.admin_alert} and the request is sent through the refund DLX into the DLQ.</li>
 *   <li>Pending refund updates are acknowledged and ignored: nothing is final yet.</li>
 * </ul>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final PaymentGateway gateway;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final PaymentEventPublisher publisher;
    private final TransactionOperations transactions;
    private final Clock clock;

    public WebhookService(PaymentGateway gateway, PaymentRepository paymentRepository, RefundService refundService,
                          PaymentEventPublisher publisher, TransactionOperations transactions, Clock clock) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.publisher = publisher;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** What the webhook did; returned to Stripe and useful in logs and tests. */
    public enum Action { PROCESSED, ALREADY_PROCESSED, IGNORED }

    public record Result(String eventId, String type, Action action) {
    }

    public Result handle(String payload, String signatureHeader) {
        GatewayWebhookEvent event = gateway.parseWebhook(payload, signatureHeader);
        Action action = switch (event.type()) {
            case "payment_intent.succeeded" -> onPaymentSucceeded(event);
            case "payment_intent.payment_failed" -> onPaymentFailed(event);
            case "charge.refunded", "refund.created", "refund.updated", "refund.failed", "charge.refund.updated" ->
                    onRefundEvent(event);
            default -> Action.IGNORED;
        };
        log.info("Stripe webhook {} ({}): {}", event.eventId(), event.type(), action);
        return new Result(event.eventId(), event.type(), action);
    }

    private Action onPaymentSucceeded(GatewayWebhookEvent event) {
        return transactions.execute(status -> findByIntent(event)
                .map(payment -> {
                    if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.FAILED) {
                        return Action.ALREADY_PROCESSED;
                    }
                    if (payment.getStatus() == PaymentStatus.FAILED) {
                        log.info("Payment {} succeeded at Stripe after an earlier decline on the same PaymentIntent;"
                                + " FAILED -> PAID", payment.getPaymentId());
                        warnIfBookingAlreadyPaid(payment);
                    }
                    String method = event.paymentMethodType() != null ? event.paymentMethodType() : "card";
                    payment.markPaid(event.paymentIntentId(), method, LocalDateTime.now(clock));
                    PaymentService.confirmBooking(payment.getBooking());
                    return Action.PROCESSED;
                })
                .orElse(Action.IGNORED));
    }

    /** Two paid payments for one booking means the customer was charged twice: someone must refund one. */
    private void warnIfBookingAlreadyPaid(Payment payment) {
        Integer bookingId = payment.getBooking().getBookingId();
        paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(bookingId).stream()
                .filter(other -> !other.getPaymentId().equals(payment.getPaymentId()))
                .filter(other -> other.getStatus() == PaymentStatus.PAID)
                .forEach(other -> log.warn("Booking {} now has two paid payments ({} and {}); refund one of them",
                        bookingId, other.getPaymentId(), payment.getPaymentId()));
    }

    private Action onPaymentFailed(GatewayWebhookEvent event) {
        return transactions.execute(status -> findByIntent(event)
                .map(payment -> {
                    if (payment.getStatus() != PaymentStatus.PENDING) {
                        return Action.ALREADY_PROCESSED;
                    }
                    payment.markFailed(event.paymentIntentId());
                    return Action.PROCESSED;
                })
                .orElse(Action.IGNORED));
    }

    private Action onRefundEvent(GatewayWebhookEvent event) {
        String refundStatus = event.refundStatus();
        if ("succeeded".equals(refundStatus)) {
            return onRefundSucceeded(event);
        }
        if ("failed".equals(refundStatus) || "canceled".equals(refundStatus)) {
            return onRefundFailed(event);
        }
        return Action.IGNORED; // pending / requires_action: Stripe hasn't finished yet
    }

    /**
     * Stripe confirms a refund. If our own refund call already marked the payment REFUNDED this is a no-op.
     * If that call left it REFUND_PENDING (Stripe answered "pending", or the call timed out) or REFUND_FAILED
     * (given up on, but Stripe did refund), this brings the payment up to date and emails the participant.
     *
     * <p>Webhooks can arrive late and out of order, so the event must be about the refund we know: for a
     * REFUND_PENDING payment the recorded refund (if any); for a REFUND_FAILED payment any refund except the one
     * Stripe already reported as failed (a stale "succeeded" snapshot of that refund is ignored).
     */
    private Action onRefundSucceeded(GatewayWebhookEvent event) {
        WebhookOutcome outcome = transactions.execute(status -> findByIntent(event)
                .map(payment -> switch (payment.getStatus()) {
                    case REFUNDED -> new WebhookOutcome(Action.ALREADY_PROCESSED, null, null);
                    case REFUND_PENDING -> isAboutRecordedRefund(payment, event)
                            ? new WebhookOutcome(Action.PROCESSED,
                                    refundService.completeRefund(payment, event.refundId()), null)
                            : ignore(event, payment);
                    case REFUND_FAILED -> {
                        String recorded = payment.getStripeRefundId();
                        if (recorded != null && (event.refundId() == null || recorded.equals(event.refundId()))) {
                            yield ignore(event, payment); // the refund we know failed: an outdated "succeeded"
                        }
                        payment.startRefund();
                        yield new WebhookOutcome(Action.PROCESSED,
                                refundService.completeRefund(payment, event.refundId()), null);
                    }
                    default -> new WebhookOutcome(Action.IGNORED, null, null);
                })
                .orElse(new WebhookOutcome(Action.IGNORED, null, null)));
        if (outcome.refunded() != null) {
            publisher.publishRefundNotification(outcome.refunded());
        }
        return outcome.action();
    }

    /**
     * Stripe reports that a refund has failed. Either it was still pending (REFUND_PENDING), or Stripe first said
     * "succeeded" and later reversed it (REFUNDED; e.g. test card {@code pm_card_refundFail}). Either way the payment
     * becomes REFUND_FAILED, the admin is alerted, and Payment publishes one message for it to the refund DLQ for
     * Admin recovery. Only failures of the refund we recorded count; others are outdated.
     */
    private Action onRefundFailed(GatewayWebhookEvent event) {
        String reason = "Stripe reported refund " + event.refundId() + " as " + event.refundStatus();
        String code = "refund_" + event.refundStatus();
        WebhookOutcome outcome = transactions.execute(status -> findByIntent(event)
                .map(payment -> switch (payment.getStatus()) {
                    case REFUND_PENDING -> isAboutRecordedRefund(payment, event)
                            ? new WebhookOutcome(Action.PROCESSED, null, refundService.failRefund(payment, reason, 0,
                                    code, false, RefundTrigger.WEBHOOK, event.eventId()))
                            : ignore(event, payment);
                    case REFUNDED -> {
                        if (event.refundId() == null || !event.refundId().equals(payment.getStripeRefundId())) {
                            yield ignore(event, payment); // about an older refund, not the one that refunded it
                        }
                        log.warn("Stripe reversed refund {} of payment {}: it failed after first succeeding",
                                event.refundId(), payment.getPaymentId());
                        payment.markRefundReversed(reason, LocalDateTime.now(clock));
                        yield new WebhookOutcome(Action.PROCESSED, null, refundService.failureEvent(payment, reason, 0,
                                code, false, RefundTrigger.WEBHOOK, event.eventId()));
                    }
                    case REFUND_FAILED -> new WebhookOutcome(Action.ALREADY_PROCESSED, null, null);
                    default -> new WebhookOutcome(Action.IGNORED, null, null);
                })
                .orElse(new WebhookOutcome(Action.IGNORED, null, null)));
        RefundFailedEvent failure = outcome.failed();
        if (failure != null) {
            publisher.publishAdminAlert(failure);
            // One individual message in payments.refunds.dlq (application-managed routing). If publishing fails, the
            // payment is REFUND_FAILED in MySQL either way, so the Admin dashboard still lists it.
            refundService.sendToDeadLetterQueue(failure);
        }
        return outcome.action();
    }

    /** True if the event is about the refund this payment recorded, or nothing is recorded yet to compare with. */
    private static boolean isAboutRecordedRefund(Payment payment, GatewayWebhookEvent event) {
        String recorded = payment.getStripeRefundId();
        return recorded == null || event.refundId() == null || recorded.equals(event.refundId());
    }

    private static WebhookOutcome ignore(GatewayWebhookEvent event, Payment payment) {
        log.info("Webhook {} ({} for refund {}) does not apply to payment {} ({}, recorded refund {}); ignoring",
                event.eventId(), event.type(), event.refundId(), payment.getPaymentId(), payment.getStatus(),
                payment.getStripeRefundId());
        return new WebhookOutcome(Action.IGNORED, null, null);
    }

    private Optional<Payment> findByIntent(GatewayWebhookEvent event) {
        if (event.paymentIntentId() == null) {
            return Optional.empty();
        }
        Optional<Payment> payment = paymentRepository.findByStripePaymentIntentId(event.paymentIntentId());
        if (payment.isEmpty()) {
            log.info("Webhook {} refers to unknown PaymentIntent {}; ignoring", event.eventId(), event.paymentIntentId());
        }
        return payment;
    }

    private record WebhookOutcome(Action action, RefundNotification refunded, RefundFailedEvent failed) {
    }
}
