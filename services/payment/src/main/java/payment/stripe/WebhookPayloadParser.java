package payment.stripe;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import payment.exception.InvalidWebhookException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the fields we need straight from the webhook JSON. Reading the raw JSON (rather than
 * Stripe's typed model) keeps working even if the account's API version differs from the SDK's.
 */
@Component
public class WebhookPayloadParser {

    private final JsonMapper jsonMapper;

    public WebhookPayloadParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public GatewayWebhookEvent parse(String payload) {
        StripeEventJson event;
        try {
            event = jsonMapper.readValue(payload, StripeEventJson.class);
        } catch (JacksonException e) {
            throw new InvalidWebhookException("Webhook body is not valid Stripe event JSON", e);
        }
        if (event == null || event.type() == null || event.data() == null || event.data().object() == null) {
            throw new InvalidWebhookException("Webhook body is missing 'type' or 'data.object'");
        }
        if (Boolean.TRUE.equals(event.livemode())) {
            // This service runs against a Stripe sandbox only; a live-mode event must never change a payment.
            throw new InvalidWebhookException("Live-mode Stripe events are not accepted: this service uses a sandbox");
        }
        StripeObjectJson object = event.data().object();
        String type = event.type();

        if (type.startsWith("payment_intent.")) {
            String methodType = object.paymentMethodTypes() == null || object.paymentMethodTypes().isEmpty()
                    ? null : object.paymentMethodTypes().get(0);
            return new GatewayWebhookEvent(event.id(), type, object.id(), null, null, methodType);
        }
        if ("charge.refunded".equals(type)) {
            String status = Boolean.TRUE.equals(object.refunded()) ? "succeeded" : "pending";
            return new GatewayWebhookEvent(event.id(), type, object.paymentIntent(), null, status, null);
        }
        // refund.created / refund.updated / refund.failed and charge.refund.updated all carry a Refund object.
        if (type.startsWith("refund.") || "charge.refund.updated".equals(type)) {
            return new GatewayWebhookEvent(event.id(), type, object.paymentIntent(), object.id(), object.status(), null);
        }
        return new GatewayWebhookEvent(event.id(), type, null, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StripeEventJson(String id, String type, Boolean livemode, DataJson data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DataJson(StripeObjectJson object) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StripeObjectJson(
            String id,
            String object,
            String status,
            Boolean refunded,
            @JsonProperty("payment_intent") String paymentIntent,
            @JsonProperty("payment_method_types") List<String> paymentMethodTypes) {
    }
}
