package payment.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import payment.service.WebhookService;

/** Receives Stripe webhooks. The raw body is needed to verify the Stripe-Signature header. */
@RestController
@RequestMapping("/payments/webhook/stripe")
public class StripeWebhookController {

    private final WebhookService webhookService;

    public StripeWebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    public WebhookService.Result receive(@RequestBody String payload,
                                         @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        return webhookService.handle(payload, signature);
    }
}
