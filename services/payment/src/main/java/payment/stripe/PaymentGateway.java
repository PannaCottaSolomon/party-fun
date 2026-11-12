package payment.stripe;

import java.util.List;
import java.util.Map;

/**
 * Everything the Payment Service needs from a payment provider. Business services depend on this
 * interface only. Its one implementation, {@link StripePaymentGateway}, calls the real Stripe API (sandbox); unit
 * tests replace it with a Mockito mock so they never reach Stripe.
 *
 * <p>All amounts are in SGD cents. Implementations throw {@link payment.exception.PaymentGatewayException}
 * for provider/network failures; a declined card is a normal {@link GatewayPaymentResult.Status#FAILED} result.
 */
public interface PaymentGateway {

    String CURRENCY = "sgd";

    /**
     * Creates a PaymentIntent. If {@code paymentMethodId} is given it is confirmed straight away.
     *
     * @param idempotencyKey the same key always returns the same PaymentIntent (no double charge)
     */
    GatewayPaymentResult createPaymentIntent(long amountCents, String paymentMethodId,
                                             String idempotencyKey, Map<String, String> metadata);

    /**
     * The current state of a PaymentIntent at Stripe. Used to record a payment the browser confirmed without waiting
     * for Stripe's webhook (for example when {@code stripe listen} isn't running locally).
     */
    GatewayPaymentResult retrievePaymentIntent(String paymentIntentId);

    /**
     * Refunds a PaymentIntent.
     *
     * @param idempotencyKey deterministic per payment, so retries and duplicates never refund twice
     */
    GatewayRefundResult refund(String paymentIntentId, long amountCents, String idempotencyKey);

    /**
     * The refunds Stripe currently has for a PaymentIntent (newest first), with their status ("succeeded",
     * "pending", "requires_action", "failed", "canceled"). Admin recovery uses it to check Stripe's actual state
     * before refunding again.
     */
    List<GatewayRefundResult> listRefunds(String paymentIntentId);

    /**
     * Verifies (when a webhook secret is configured) and parses a Stripe webhook body.
     *
     * @throws payment.exception.InvalidWebhookException on a bad signature or unreadable body
     */
    GatewayWebhookEvent parseWebhook(String payload, String signatureHeader);
}
