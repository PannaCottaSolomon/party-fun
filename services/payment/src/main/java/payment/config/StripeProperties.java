package payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe credentials, read from the {@code stripe_secret_key}, {@code stripe_webhook_secret} and
 * {@code stripe_publishable_key} environment variables. The two secrets are never committed and never logged.
 *
 * <p>Sandbox only: a secret key that is not a sandbox/test key ({@code sk_test_} or restricted {@code rk_test_})
 * stops the service from starting, so a live key can never cause a real charge. The publishable key is public by
 * design (the frontend's Stripe.js card form uses it, served by {@code GET /payments/config}), so it must be a
 * sandbox publishable key ({@code pk_test_}); a secret key put there by mistake would reach every browser and is
 * refused too.
 */
@ConfigurationProperties(prefix = "stripe")
public record StripeProperties(String secretKey, String webhookSecret, String publishableKey) {

    public StripeProperties {
        // Never include a key itself in these messages: they end up in the logs.
        if (secretKey != null && !secretKey.isBlank()
                && !secretKey.startsWith("sk_test_") && !secretKey.startsWith("rk_test_")) {
            throw new IllegalArgumentException(secretKey.startsWith("pk_")
                    ? "stripe_secret_key holds a publishable key (pk_...); use the sandbox Secret key (sk_test_...)"
                    : "stripe_secret_key must be a Stripe sandbox key (sk_test_... or rk_test_...); live keys are"
                            + " refused so no real payment can be made");
        }
        if (publishableKey != null && !publishableKey.isBlank() && !publishableKey.startsWith("pk_test_")) {
            throw new IllegalArgumentException(publishableKey.startsWith("sk_") || publishableKey.startsWith("rk_")
                    ? "stripe_publishable_key holds a secret key, which would be sent to browsers; use the sandbox"
                            + " Publishable key (pk_test_...)"
                    : "stripe_publishable_key must be a Stripe sandbox publishable key (pk_test_...)");
        }
    }

    public boolean hasSecretKey() {
        return secretKey != null && !secretKey.isBlank();
    }

    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    @Override
    public String toString() {
        return "StripeProperties[secretKey=" + (hasSecretKey() ? "***" : "<unset>")
                + ", webhookSecret=" + (hasWebhookSecret() ? "***" : "<unset>")
                + ", publishableKey=" + (publishableKey == null || publishableKey.isBlank() ? "<unset>" : publishableKey)
                + "]";
    }
}
