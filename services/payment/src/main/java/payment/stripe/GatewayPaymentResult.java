package payment.stripe;

/**
 * Result of creating a PaymentIntent.
 *
 * @param clientSecret only set while the payment still needs confirming by the frontend
 */
public record GatewayPaymentResult(
        String paymentIntentId,
        Status status,
        String clientSecret,
        String paymentMethodType,
        String failureReason) {

    public enum Status {
        SUCCEEDED,
        /** Created but not confirmed yet (frontend confirms; the webhook completes it). */
        PENDING,
        /** Declined or cancelled. */
        FAILED
    }

    public static GatewayPaymentResult succeeded(String paymentIntentId, String paymentMethodType) {
        return new GatewayPaymentResult(paymentIntentId, Status.SUCCEEDED, null, paymentMethodType, null);
    }

    public static GatewayPaymentResult pending(String paymentIntentId, String clientSecret) {
        return new GatewayPaymentResult(paymentIntentId, Status.PENDING, clientSecret, null, null);
    }

    public static GatewayPaymentResult failed(String paymentIntentId, String failureReason) {
        return new GatewayPaymentResult(paymentIntentId, Status.FAILED, null, null, failureReason);
    }
}
