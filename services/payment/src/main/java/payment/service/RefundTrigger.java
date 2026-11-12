package payment.service;

/** What started a refund. Included in admin alerts and failed-refund (DLQ) messages. */
public enum RefundTrigger {
    /** event.cancelled from the Event Service (choreography Saga). */
    EVENT_CANCELLED,
    /** A REST call to one of the refund endpoints (POST /payments/{id}/refund, POST /payments/booking/{id}/refund). */
    MANUAL,
    /** An Admin recovering a REFUND_FAILED payment (POST /admin/refunds/{paymentId}/retry). */
    ADMIN_RETRY,
    /** RefundRecoveryJob resuming a refund that was abandoned mid-way (e.g. the service stopped). */
    RECOVERY,
    /** A Stripe webhook reported that a refund failed. */
    WEBHOOK
}
