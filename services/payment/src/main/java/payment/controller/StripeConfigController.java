package payment.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import payment.config.StripeProperties;
import payment.dto.StripeConfigResponse;
import payment.exception.ServiceNotConfiguredException;

/**
 * Public Stripe settings for the frontend. Only the publishable key is exposed: it is meant for browsers, and
 * {@link StripeProperties} refuses anything other than a sandbox publishable key there. Without a key the card form
 * cannot work, so the endpoint answers 503 instead of an empty key.
 */
@RestController
public class StripeConfigController {

    private final StripeProperties stripe;

    public StripeConfigController(StripeProperties stripe) {
        this.stripe = stripe;
    }

    @GetMapping("/payments/config")
    public StripeConfigResponse config() {
        String key = stripe.publishableKey();
        if (key == null || key.isBlank()) {
            throw new ServiceNotConfiguredException(
                    "Stripe card payments are not configured: stripe_publishable_key is not set");
        }
        return new StripeConfigResponse(key);
    }
}
