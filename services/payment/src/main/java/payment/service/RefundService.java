package payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import payment.config.PaymentProperties;
import payment.dto.BookingRefundResponse;
import payment.dto.RefundResultResponse;
import payment.dto.RefundResultResponse.Outcome;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.exception.NotFoundException;
import payment.exception.PaymentGatewayException;
import payment.exception.RefundFailedException;
import payment.messaging.FailedRefundMessage;
import payment.messaging.PaymentEventPublisher;
import payment.messaging.RefundFailedEvent;
import payment.messaging.RefundNotification;
import payment.messaging.RefundRequestedMessage;
import payment.repository.BookingRepository;
import payment.repository.PaymentRepository;
import payment.stripe.GatewayRefundResult;
import payment.stripe.Money;
import payment.stripe.PaymentGateway;

/**
 * Refunds: the Payment Service's part of the choreography-based cancellation Saga, plus the REST refunds.
 *
 * <p>For each payment: claim it (PAID → REFUND_PENDING), then call Stripe up to
 * {@code payment.refund.max-attempts} (3) times in total (1 initial attempt + 2 retries) with the same idempotency
 * key. Only transient errors are retried; a permanent one (e.g. Stripe says the charge is already refunded or
 * disputed) stops at once.
 * <ul>
 *   <li>Stripe confirms ({@code succeeded}) → REFUNDED, and {@code notify.refund} goes to the participant.</li>
 *   <li>Stripe accepts but has not finished ({@code pending}) → stays REFUND_PENDING with the refund id; the
 *       Stripe webhook completes it later. It is never reported as refunded early.</li>
 *   <li>Still failing → REFUND_FAILED in MySQL (reason, attempts, time), an admin alert ({@code notify.admin_alert})
 *       and one individual message that Payment publishes to {@code payments.refund.dlx} →
 *       {@code payments.refunds.dlq} for Admin recovery (application-managed routing).</li>
 * </ul>
 *
 * <p>This is a retry Saga, not a rollback Saga: nothing is compensated. A cancelled event and its
 * cancelled bookings stay cancelled even when a refund fails; the failure goes to a human via the DLQ.
 *
 * <p>Idempotency: REFUNDED payments are never sent to Stripe again, the payment row is locked while
 * it is claimed, and the Stripe idempotency key {@code refund-{paymentIntentId}} (plus {@code -retry-{n}} for the
 * n-th Admin retry, see {@link #idempotencyKey}) means even a duplicate call returns the original refund instead of
 * creating a second one.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /**
     * Payments the event-cancellation flow refunds: PAID, and REFUND_PENDING (an interrupted earlier run, resumed with
     * the same key). REFUND_FAILED ones are already in the DLQ and belong to the Admin.
     */
    private static final Set<PaymentStatus> EVENT_REFUNDABLE = EnumSet.of(PaymentStatus.PAID, PaymentStatus.REFUND_PENDING);
    /** Statuses meaning "money was taken and not yet given back". */
    private static final Set<PaymentStatus> HOLDING_MONEY =
            EnumSet.of(PaymentStatus.PAID, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_FAILED);

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final PaymentGateway gateway;
    private final PaymentEventPublisher publisher;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final int maxAttempts;
    private final long backoffMs;

    public RefundService(PaymentRepository paymentRepository, BookingRepository bookingRepository,
                         PaymentGateway gateway, PaymentEventPublisher publisher,
                         TransactionOperations transactions, Clock clock, PaymentProperties properties) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.gateway = gateway;
        this.publisher = publisher;
        this.transactions = transactions;
        this.clock = clock;
        this.maxAttempts = Math.max(1, properties.refund().maxAttempts());
        this.backoffMs = Math.max(0, properties.refund().backoffMs());
    }

    /** {@code POST /payments/{id}/refund}: one refund; a final failure becomes HTTP 502 (and is in the DLQ). */
    public RefundResultResponse refundPaymentManually(Integer paymentId) {
        RefundResultResponse result = refundAndReport(paymentId, RefundTrigger.MANUAL, null);
        if (result.outcome() == Outcome.FAILED) {
            throw new RefundFailedException("Refund for payment " + paymentId + " failed after " + result.attempts()
                    + " attempts; it is marked REFUND_FAILED and was sent to the refund dead-letter queue");
        }
        return result;
    }

    /**
     * Refunds every paid payment of a booking. Each payment is handled on its own: one failure does
     * not undo or block the others, and REFUNDED payments are reported but not touched.
     */
    public BookingRefundResponse refundBooking(Integer bookingId) {
        if (!bookingRepository.existsById(bookingId)) {
            throw new NotFoundException("Booking " + bookingId + " not found");
        }
        List<Payment> payments = paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(bookingId);
        boolean anythingPaid = payments.stream()
                .anyMatch(p -> HOLDING_MONEY.contains(p.getStatus()) || p.getStatus() == PaymentStatus.REFUNDED);
        if (!anythingPaid) {
            throw new InvalidStateException("Booking " + bookingId + " has no paid payments to refund");
        }
        List<RefundResultResponse> results = new ArrayList<>();
        for (Payment payment : payments) {
            if (HOLDING_MONEY.contains(payment.getStatus()) || payment.getStatus() == PaymentStatus.REFUNDED) {
                results.add(refundAndReport(payment.getPaymentId(), RefundTrigger.MANUAL, null));
            } else {
                results.add(new RefundResultResponse(payment.getPaymentId(), Outcome.SKIPPED, payment.getStatus(),
                        null, 0, "Payment was never paid"));
            }
        }
        return new BookingRefundResponse(bookingId, results);
    }

    /**
     * Handles {@code event.cancelled} (choreography Saga step): cancels the event's open bookings, then refunds every
     * eligible payment of the event <b>individually, inside this service</b> (no per-payment queue).
     *
     * <ul>
     *   <li>Each payment is refunded on its own: a failing refund never blocks or undoes the others.</li>
     *   <li>Refunded: REFUNDED and {@code notify.refund} to the participant.</li>
     *   <li>Still failing after its attempts: REFUND_FAILED in MySQL (with reason and attempts), one individual
     *       message published to {@code payments.refund.dlx} → {@code payments.refunds.dlq}, and
     *       {@code notify.admin_alert}.</li>
     * </ul>
     *
     * <p>Crash safety: the caller acks the event.cancelled message only after this returns, so if the service stops
     * mid-way RabbitMQ redelivers it. Re-running is safe and so is a duplicate cancellation: REFUNDED payments are
     * skipped without calling Stripe, REFUND_FAILED ones are already in the DLQ (the Admin owns them), and
     * REFUND_PENDING ones resume with the same Stripe idempotency key, so nothing is refunded twice.
     *
     * <p>An unexpected error (e.g. MySQL down) propagates, so the message is redelivered rather than lost.
     */
    public EventRefundSummary refundEvent(RefundRequestedMessage request) {
        Integer eventId = request.eventId();
        String requestId = request.requestId() != null ? request.requestId() : "cancel-event-" + eventId;
        List<Integer> paymentIds = transactions.execute(status -> {
            for (Booking booking : bookingRepository.findByEventIdAndStatusIn(eventId,
                    EnumSet.of(BookingStatus.PENDING, BookingStatus.CONFIRMED))) {
                booking.transitionTo(BookingStatus.CANCELLED);
            }
            return paymentRepository.findByEventIdAndStatusIn(eventId, EVENT_REFUNDABLE).stream()
                    .map(Payment::getPaymentId)
                    .toList();
        });
        log.info("Event {} cancelled (requestId={}): refunding {} payment(s) one by one", eventId, requestId,
                paymentIds.size());
        List<RefundResultResponse> results = new ArrayList<>();
        for (Integer paymentId : paymentIds) {
            try {
                results.add(refundAndReport(paymentId, RefundTrigger.EVENT_CANCELLED, requestId));
            } catch (NotFoundException | InvalidStateException e) {
                // Changed by something else meanwhile (e.g. a webhook): nothing to do for this one.
                log.warn("Skipping refund of payment {} for event {}: {}", paymentId, eventId, e.getMessage());
            }
        }
        EventRefundSummary summary = EventRefundSummary.of(eventId, results);
        log.info("Event {} refunds: {} refunded, {} pending at Stripe, {} failed (in the refund DLQ)", eventId,
                summary.refunded(), summary.pending(), summary.failed());
        return summary;
    }

    /**
     * Refunds one payment with up to {@code maxAttempts} Stripe calls and, if it still fails, reports it: the
     * admin alert and one individual message in the refund DLQ. Never throws for Stripe failures.
     */
    public RefundResultResponse refundAndReport(Integer paymentId, RefundTrigger trigger, String requestId) {
        RefundAttempt attempt = attemptRefund(paymentId, trigger, requestId);
        if (attempt.failure() != null) {
            sendToDeadLetterQueue(attempt.failure());
        }
        return attempt.result();
    }

    /**
     * Refunds one payment without publishing anything to the DLQ on failure (the admin alert is still sent). Used by
     * Admin recovery, which already holds the payment's DLQ message and settles it itself.
     */
    public RefundAttemptResult refundForRecovery(Integer paymentId, String requestId) {
        RefundAttempt attempt = attemptRefund(paymentId, RefundTrigger.ADMIN_RETRY, requestId);
        return new RefundAttemptResult(attempt.result(), attempt.failure());
    }

    /**
     * Publishes one finally failed refund to {@code payments.refund.dlx} (→ {@code payments.refunds.dlq}) and waits
     * for the broker's confirm. If that fails, the failure is still recorded in MySQL (REFUND_FAILED with its
     * reason), so the Admin dashboard still lists it and the Admin can recover it.
     */
    public void sendToDeadLetterQueue(RefundFailedEvent failure) {
        try {
            publisher.publishFailedRefund(FailedRefundMessage.from(failure));
        } catch (AmqpException e) {
            log.error("Payment {} is REFUND_FAILED in MySQL but its failed-refund message could not be published to "
                    + "the refund DLQ ({}); it is still listed on the Admin dashboard", failure.paymentId(),
                    e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Admin recovery steps (AdminRefundService)

    /**
     * Admin recovery, step 1: claims a REFUND_FAILED payment under a row lock (REFUND_FAILED → REFUND_PENDING, a new
     * refund round). Only one Admin can hold the claim: a second one gets 409. A payment that is already REFUNDED is
     * reported as such (its DLQ message is then stale).
     */
    public RecoveryClaim claimForRecovery(Integer paymentId) {
        return transactions.execute(status -> {
            Payment payment = lockedLoad(paymentId);
            switch (payment.getStatus()) {
                case REFUNDED -> {
                    return RecoveryClaim.alreadyRefunded(payment.getStripeRefundId());
                }
                case REFUND_FAILED -> {
                    payment.startRefund();
                    return RecoveryClaim.claimed(payment.getStripePaymentIntentId());
                }
                case REFUND_PENDING -> throw new InvalidStateException("Payment " + paymentId
                        + " is REFUND_PENDING: a refund of it is already in progress (or another Admin is recovering it)");
                default -> throw new InvalidStateException("Payment " + paymentId + " is " + payment.getStatus()
                        + "; only REFUND_FAILED payments can be recovered");
            }
        });
    }

    /** Admin recovery: Stripe already shows a succeeded refund for the payment, so record it (no new refund). */
    public RefundResultResponse reconcileSucceededRefund(Integer paymentId, String refundId) {
        RefundNotification notification = transactions.execute(status ->
                completeRefund(lockedLoad(paymentId), refundId));
        if (notification != null) {
            publisher.publishRefundNotification(notification);
        }
        return new RefundResultResponse(paymentId, Outcome.REFUNDED, PaymentStatus.REFUNDED, refundId, 0,
                "Stripe already shows this refund as succeeded; recorded it without refunding again");
    }

    /** Admin recovery: a refund is still pending at Stripe, so wait for its webhook (no new refund). */
    public RefundResultResponse reconcilePendingRefund(Integer paymentId, String refundId) {
        return recordPending(paymentId, new GatewayRefundResult(refundId, "pending"), 0);
    }

    /** Admin recovery could not even check Stripe: give the claim back (REFUND_PENDING → REFUND_FAILED). */
    public void releaseRecoveryClaim(Integer paymentId, String reason) {
        transactions.executeWithoutResult(status -> {
            Payment payment = lockedLoad(paymentId);
            if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
                payment.markRefundFailed(reason, 0, LocalDateTime.now(clock));
            }
        });
    }

    /** The admin-alert / DLQ details of a payment that is REFUND_FAILED, e.g. to re-publish a missing DLQ message. */
    public RefundFailedEvent currentFailure(Integer paymentId, RefundTrigger trigger) {
        return transactions.execute(status -> {
            Payment payment = lockedLoad(paymentId);
            return failureEvent(payment, payment.getRefundFailureReason(), payment.getRefundAttempts(), null, false,
                    trigger, null);
        });
    }

    private RefundAttempt attemptRefund(Integer paymentId, RefundTrigger trigger, String requestId) {
        RefundTarget target = transactions.execute(status -> claim(paymentId));
        if (target.alreadyRefunded()) {
            return new RefundAttempt(new RefundResultResponse(paymentId, Outcome.ALREADY_REFUNDED, PaymentStatus.REFUNDED,
                    target.stripeRefundId(), 0, "Payment was already refunded; nothing sent to Stripe"), null);
        }

        String idempotencyKey = idempotencyKey(target.paymentIntentId(), target.round());
        long amountCents = Money.toCents(target.amount());
        GatewayRefundResult refund = null;
        String lastError = null;
        String errorCode = null;
        boolean retryable = true;
        int attempts = 0;
        while (refund == null && attempts < maxAttempts) {
            attempts++;
            try {
                refund = gateway.refund(target.paymentIntentId(), amountCents, idempotencyKey);
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                PaymentGatewayException gatewayError = e instanceof PaymentGatewayException g ? g : null;
                errorCode = gatewayError != null ? gatewayError.getErrorCode() : null;
                retryable = gatewayError == null || gatewayError.isRetryable();
                log.warn("Refund attempt {}/{} for payment {} failed ({}): {}", attempts, maxAttempts, paymentId,
                        retryable ? "retryable" : "permanent", lastError);
                if (!retryable || attempts >= maxAttempts || !pause(backoffMs * attempts)) {
                    break;
                }
            }
        }

        if (refund != null && refund.succeeded()) {
            String refundId = refund.refundId();
            RefundNotification notification = transactions.execute(status ->
                    completeRefund(lockedLoad(paymentId), refundId));
            if (notification != null) {
                publisher.publishRefundNotification(notification);
            }
            return new RefundAttempt(new RefundResultResponse(paymentId, Outcome.REFUNDED, PaymentStatus.REFUNDED,
                    refundId, attempts, "Refunded"), null);
        }
        if (refund != null) {
            return new RefundAttempt(recordPending(paymentId, refund, attempts), null);
        }

        int attemptsMade = attempts;
        String reason = lastError;
        String code = errorCode;
        boolean transientError = retryable;
        RefundFailedEvent failure = transactions.execute(status -> {
            Payment payment = lockedLoad(paymentId);
            if (payment.getStatus() == PaymentStatus.REFUNDED) {
                return null; // a Stripe webhook confirmed the refund while we were retrying
            }
            return failRefund(payment, reason, attemptsMade, code, transientError, trigger, requestId);
        });
        if (failure == null) {
            return new RefundAttempt(new RefundResultResponse(paymentId, Outcome.ALREADY_REFUNDED,
                    PaymentStatus.REFUNDED, null, attempts, "Stripe confirmed the refund via webhook"), null);
        }
        publisher.publishAdminAlert(failure);
        return new RefundAttempt(new RefundResultResponse(paymentId, Outcome.FAILED, PaymentStatus.REFUND_FAILED, null,
                attempts, reason), failure);
    }

    /** Stripe accepted the refund but hasn't completed it: keep REFUND_PENDING, remember the refund id. */
    private RefundResultResponse recordPending(Integer paymentId, GatewayRefundResult refund, int attempts) {
        boolean alreadyRefunded = Boolean.TRUE.equals(transactions.execute(status -> {
            Payment payment = lockedLoad(paymentId);
            if (payment.getStatus() == PaymentStatus.REFUNDED) {
                return true; // the webhook was faster than us
            }
            payment.recordPendingRefund(refund.refundId());
            return false;
        }));
        if (alreadyRefunded) {
            return new RefundResultResponse(paymentId, Outcome.ALREADY_REFUNDED, PaymentStatus.REFUNDED,
                    refund.refundId(), attempts, "Stripe confirmed the refund via webhook");
        }
        log.info("Refund {} for payment {} is {} at Stripe; waiting for the webhook to confirm it",
                refund.refundId(), paymentId, refund.status());
        return new RefundResultResponse(paymentId, Outcome.PENDING, PaymentStatus.REFUND_PENDING, refund.refundId(),
                attempts, "Stripe accepted the refund (" + refund.status() + "); it completes when Stripe confirms it");
    }

    /**
     * Marks a payment REFUNDED (inside the caller's transaction) and, once nothing on the booking is
     * still paid, the booking too. Returns what the participant's {@code notify.refund} needs, to publish after
     * commit, or null if it was already REFUNDED (so a duplicate never emails twice). Also used by the webhook.
     */
    RefundNotification completeRefund(Payment payment, String refundId) {
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        payment.markRefunded(refundId, now);
        Booking booking = payment.getBooking();
        boolean stillPaid = paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(booking.getBookingId()).stream()
                .anyMatch(p -> !p.getPaymentId().equals(payment.getPaymentId()) && HOLDING_MONEY.contains(p.getStatus()));
        if (!stillPaid && booking.getStatus().canTransitionTo(BookingStatus.REFUNDED)) {
            booking.transitionTo(BookingStatus.REFUNDED);
        }
        return RefundNotification.of(payment);
    }

    /**
     * Marks a refund as finally failed (inside the caller's transaction) and returns the details for the admin alert
     * and the DLQ. Also used by the webhook when Stripe reports a pending refund as failed.
     */
    RefundFailedEvent failRefund(Payment payment, String reason, int attempts, String errorCode, boolean retryable,
                                 RefundTrigger trigger, String requestId) {
        payment.markRefundFailed(reason, attempts, LocalDateTime.now(clock));
        return failureEvent(payment, reason, attempts, errorCode, retryable, trigger, requestId);
    }

    /** The admin-alert / DLQ details for a payment that is already REFUND_FAILED. */
    RefundFailedEvent failureEvent(Payment payment, String reason, int attempts, String errorCode, boolean retryable,
                                   RefundTrigger trigger, String requestId) {
        Booking booking = payment.getBooking();
        return new RefundFailedEvent(RefundFailedEvent.TYPE, payment.getPaymentId(), booking.getBookingId(),
                booking.getEventId(), booking.getUserId(), payment.getAmount(), Money.toCents(payment.getAmount()),
                "SGD", payment.getStripePaymentIntentId(), payment.getStripeRefundId(), reason, attempts, errorCode,
                retryable, trigger.name(), requestId, clock.instant());
    }

    private RefundTarget claim(Integer paymentId) {
        Payment payment = lockedLoad(paymentId);
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return RefundTarget.refunded(payment.getStripeRefundId());
        }
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
            // PAID or REFUND_FAILED -> REFUND_PENDING (a retry of REFUND_FAILED starts a new round);
            // anything else throws InvalidStateException (409).
            payment.startRefund();
        }
        // REFUND_PENDING: an earlier attempt was interrupted; the round's idempotency key makes resuming safe.
        return new RefundTarget(false, null, payment.getStripePaymentIntentId(), payment.getAmount(),
                payment.getRefundRound());
    }

    /**
     * Stripe idempotency key of a refund round. Every attempt of one round reuses it, so a lost response can never
     * create a second refund. A retry of a REFUND_FAILED payment is a new round with a new key, because Stripe
     * replays the first answer for a key (failures included) for at least 24 hours. A new key cannot refund twice:
     * Stripe never refunds more than the charge.
     *
     * <p>The key is built from the PaymentIntent id, not the payment id: Stripe ids are unique across the whole
     * sandbox, while payment ids restart at 1 in every database (CI, each developer's stack) that shares it, and a
     * reused key would replay or reject another environment's refund.
     */
    static String idempotencyKey(String paymentIntentId, int round) {
        return round == 0 ? "refund-" + paymentIntentId : "refund-" + paymentIntentId + "-retry-" + round;
    }

    private Payment lockedLoad(Integer paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
    }

    /** Waits between attempts. Returns false if the thread was interrupted (stop retrying). */
    private static boolean pause(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The outcome of one refund, plus the failure details when every attempt failed. */
    private record RefundAttempt(RefundResultResponse result, RefundFailedEvent failure) {
    }

    /** {@link #refundForRecovery}'s outcome: the result, plus the failure details when it failed again. */
    public record RefundAttemptResult(RefundResultResponse result, RefundFailedEvent failure) {
    }

    /** {@link #claimForRecovery}'s outcome. */
    public record RecoveryClaim(boolean alreadyRefunded, String stripeRefundId, String paymentIntentId) {

        static RecoveryClaim alreadyRefunded(String stripeRefundId) {
            return new RecoveryClaim(true, stripeRefundId, null);
        }

        static RecoveryClaim claimed(String paymentIntentId) {
            return new RecoveryClaim(false, null, paymentIntentId);
        }
    }

    private record RefundTarget(boolean alreadyRefunded, String stripeRefundId, String paymentIntentId,
                                BigDecimal amount, int round) {

        static RefundTarget refunded(String stripeRefundId) {
            return new RefundTarget(true, stripeRefundId, null, null, 0);
        }
    }
}
