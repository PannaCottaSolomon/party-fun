package payment.dto;

/**
 * Body of {@code GET /payments/config}: what a frontend needs to show Stripe's card form (Stripe.js). The
 * publishable key is public by design and is always a sandbox key ({@code pk_test_...}), never null:
 * without {@code stripe_publishable_key} the endpoint answers 503 instead.
 */
public record StripeConfigResponse(String stripePublishableKey) {
}
