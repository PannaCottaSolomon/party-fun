package payment.entity;

/**
 * Booking lifecycle.
 *
 * <pre>
 *   PENDING ──paid──▶ CONFIRMED ──refunded──▶ REFUNDED
 *      │                  │                       ▲
 *      └──event cancelled─┴──▶ CANCELLED ──refunded┘
 * </pre>
 *
 * CANCELLED never goes back to PENDING or CONFIRMED: a cancelled event stays cancelled (retry Saga, no rollback).
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    REFUNDED;

    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case PENDING -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == CANCELLED || next == REFUNDED;
            case CANCELLED -> next == REFUNDED;
            case REFUNDED -> false;
        };
    }
}
