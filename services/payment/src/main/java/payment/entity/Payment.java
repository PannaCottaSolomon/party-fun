package payment.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import payment.exception.InvalidStateException;

/**
 * One Stripe payment attempt for a booking. Amounts are SGD (there is no currency column).
 * Status only changes through the business methods below, which enforce {@link PaymentStatus} rules.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Integer paymentId;

    // The only real foreign key in this service: payment.booking_id -> booking.booking_id
    @ManyToOne(optional = false)
    @JoinColumn(name = "booking_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payment_booking"))
    private Booking booking;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "stripe_refund_id")
    private String stripeRefundId;

    // 0 for the first refund attempt; +1 each time a REFUND_FAILED payment is retried. Part of the Stripe
    // idempotency key, because Stripe replays the first answer for a key (failures included) for 24 h.
    @Column(name = "refund_round", nullable = false)
    private int refundRound;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    // Why the last refund round finally failed, how many Stripe attempts it made and when. Set while REFUND_FAILED
    // (the Admin dashboard reads them), cleared once a refund succeeds. MySQL, not the DLQ, is authoritative.
    @Column(name = "refund_failure_reason", length = FAILURE_REASON_LENGTH)
    private String refundFailureReason;

    @Column(name = "refund_attempts", nullable = false)
    private int refundAttempts;

    @Column(name = "refund_failed_at")
    private LocalDateTime refundFailedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    static final int FAILURE_REASON_LENGTH = 500;

    protected Payment() {
        // for JPA
    }

    public Payment(Booking booking, BigDecimal amount) {
        this.booking = booking;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    /** Records the PaymentIntent created for a payment that still awaits confirmation. */
    public void attachPaymentIntent(String paymentIntentId) {
        this.stripePaymentIntentId = paymentIntentId;
    }

    public void markPaid(String paymentIntentId, String method, LocalDateTime at) {
        transitionTo(PaymentStatus.PAID);
        if (paymentIntentId != null) {
            this.stripePaymentIntentId = paymentIntentId;
        }
        this.paymentMethod = method;
        this.paidAt = at;
    }

    public void markFailed(String paymentIntentId) {
        transitionTo(PaymentStatus.FAILED);
        if (paymentIntentId != null) {
            this.stripePaymentIntentId = paymentIntentId;
        }
    }

    /**
     * PAID or REFUND_FAILED -> REFUND_PENDING. Retrying a REFUND_FAILED payment starts a new refund round: a new
     * Stripe idempotency key, and the failed refund's id is forgotten.
     */
    public void startRefund() {
        boolean retry = status == PaymentStatus.REFUND_FAILED;
        transitionTo(PaymentStatus.REFUND_PENDING);
        if (retry) {
            refundRound++;
            stripeRefundId = null;
            refundedAt = null;
        }
    }

    public void markRefunded(String refundId, LocalDateTime at) {
        transitionTo(PaymentStatus.REFUNDED);
        if (refundId != null) {
            this.stripeRefundId = refundId;
        }
        this.refundedAt = at;
        this.refundFailureReason = null;
        this.refundFailedAt = null;
    }

    /**
     * Stripe accepted the refund but has not completed it yet ("pending"): keep REFUND_PENDING and remember the
     * refund id. The Stripe webhook later confirms it (REFUNDED) or reports it failed (REFUND_FAILED).
     */
    public void recordPendingRefund(String refundId) {
        if (status != PaymentStatus.REFUND_PENDING) {
            throw new InvalidStateException(
                    "Payment %d is %s; a pending refund can only be recorded while REFUND_PENDING"
                            .formatted(paymentId, status));
        }
        this.stripeRefundId = refundId;
    }

    /**
     * A refund round finally failed: REFUND_PENDING -> REFUND_FAILED, recording why, after how many Stripe attempts
     * and when (a REFUNDED payment uses markRefundReversed).
     */
    public void markRefundFailed(String reason, int attempts, LocalDateTime at) {
        if (status != PaymentStatus.REFUND_PENDING) {
            throw new InvalidStateException(
                    "Payment %d cannot change from %s to REFUND_FAILED".formatted(paymentId, status));
        }
        transitionTo(PaymentStatus.REFUND_FAILED);
        recordFailure(reason, attempts, at);
    }

    /**
     * Stripe reported the refund that completed this payment as failed after all (it first said "succeeded"):
     * REFUNDED -> REFUND_FAILED. The failed refund's id stays recorded so late or repeated events about it can be
     * recognised.
     */
    public void markRefundReversed(String reason, LocalDateTime at) {
        if (status != PaymentStatus.REFUNDED) {
            throw new InvalidStateException(
                    "Payment %d is %s; only a REFUNDED payment can have its refund reversed".formatted(paymentId, status));
        }
        transitionTo(PaymentStatus.REFUND_FAILED);
        refundedAt = null;
        recordFailure(reason, 0, at);
    }

    private void recordFailure(String reason, int attempts, LocalDateTime at) {
        String text = reason == null || reason.isBlank() ? "Unknown refund failure" : reason;
        this.refundFailureReason = text.length() > FAILURE_REASON_LENGTH ? text.substring(0, FAILURE_REASON_LENGTH)
                : text;
        this.refundAttempts = attempts;
        this.refundFailedAt = at;
    }

    private void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateException(
                    "Payment %d cannot change from %s to %s".formatted(paymentId, status, next));
        }
        status = next;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getPaymentId() {
        return paymentId;
    }

    public Booking getBooking() {
        return booking;
    }

    public String getStripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    public String getStripeRefundId() {
        return stripeRefundId;
    }

    public int getRefundRound() {
        return refundRound;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public String getRefundFailureReason() {
        return refundFailureReason;
    }

    public int getRefundAttempts() {
        return refundAttempts;
    }

    public LocalDateTime getRefundFailedAt() {
        return refundFailedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
