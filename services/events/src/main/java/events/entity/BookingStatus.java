package events.entity;

public enum BookingStatus {
    PENDING,    // place reserved, waiting for payment (until expiresAt); a failed payment keeps it PENDING
                // so the user can retry payment without losing the place
    CONFIRMED,  // payment succeeded (first attempt or a retry before expiresAt)
    EXPIRED,    // no successful payment before expiresAt (including after failed attempts) -> place released
    CANCELLED   // event deleted or cancelled, or the payment was refunded (Booking.refundedAt records the refund)

    // No REFUNDED value on purpose: Hibernate generates a CHECK constraint listing these values, and
    // ddl-auto=update never changes an existing constraint, so a new value would break existing databases.
}
