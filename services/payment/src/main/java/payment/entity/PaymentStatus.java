package payment.entity;

/**
 * Payment lifecycle. Only these transitions are allowed; clients can never set a status directly.
 *
 * <pre>
 *   PENDING ──▶ PAID ──▶ REFUND_PENDING ──▶ REFUNDED
 *      │         ▲           │    ▲             │
 *      ▼         │           ▼    │ (retry)     │ Stripe later reports that refund as failed
 *    FAILED ─────┘       REFUND_FAILED ◀────────┘ (webhook, e.g. test card pm_card_refundFail)
 * </pre>
 *
 * <p>FAILED → PAID only happens through the {@code payment_intent.succeeded} webhook for the same PaymentIntent:
 * with Stripe.js a customer can try another card on a PaymentIntent whose first card was declined, and Stripe is
 * the source of truth for whether money was taken.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING -> next == PAID || next == FAILED;
            case PAID -> next == REFUND_PENDING;
            case REFUND_PENDING -> next == REFUNDED || next == REFUND_FAILED;
            case REFUND_FAILED -> next == REFUND_PENDING;
            case REFUNDED -> next == REFUND_FAILED;
            case FAILED -> next == PAID;
        };
    }
}
