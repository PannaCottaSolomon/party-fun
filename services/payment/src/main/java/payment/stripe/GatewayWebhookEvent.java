package payment.stripe;

/**
 * The parts of a Stripe webhook event this service uses.
 *
 * @param eventId           Stripe event id (evt_...)
 * @param type              e.g. payment_intent.succeeded, charge.refunded, refund.updated
 * @param paymentIntentId   the PaymentIntent the event is about (pi_...), or null
 * @param refundId          refund id (re_...) for refund events, if present
 * @param refundStatus      "succeeded", "pending", "failed"... for refund events
 * @param paymentMethodType e.g. "card", for payment_intent events
 */
public record GatewayWebhookEvent(
        String eventId,
        String type,
        String paymentIntentId,
        String refundId,
        String refundStatus,
        String paymentMethodType) {
}
