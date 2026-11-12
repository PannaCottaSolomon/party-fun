package payment.stripe;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;

import payment.config.StripeProperties;
import payment.exception.InvalidWebhookException;
import payment.exception.PaymentGatewayException;

/**
 * The {@link PaymentGateway}: calls the real Stripe API (api.stripe.com) through the Stripe Java SDK, always in a
 * Stripe sandbox ({@code sk_test_} key; {@link StripeProperties} refuses live keys, so no real money moves).
 * The only class in the service that touches the Stripe SDK.
 */
@Component
public class StripePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    private final StripeClient client;
    private final StripeProperties properties;
    private final WebhookPayloadParser parser;

    public StripePaymentGateway(StripeProperties properties, WebhookPayloadParser parser) {
        this(properties, parser, client(properties, ""));
    }

    /**
     * {@code apiBase}: where "Stripe" is. Empty = api.stripe.com. Only the {@code test} profile sets it (the integration
     * tests' WireMock container, {@code stripe_api_base}); no other profile reads that variable, so the running service
     * always talks to the real Stripe sandbox.
     */
    @Autowired
    public StripePaymentGateway(StripeProperties properties, WebhookPayloadParser parser,
                                @Value("${stripe.api-base:}") String apiBase, Environment environment) {
        this(properties, parser, client(properties, testOnly(apiBase, environment)));
    }

    /** Refuses to start if anything but the test profile tries to send Stripe calls elsewhere. */
    private static String testOnly(String apiBase, Environment environment) {
        if (apiBase != null && !apiBase.isBlank() && !environment.matchesProfiles("test")) {
            throw new IllegalStateException("stripe api-base can only be set with stage=test (integration tests); the"
                    + " service itself must call the real Stripe sandbox");
        }
        return apiBase;
    }

    /** Tests only: a client pointed at a local stand-in for api.stripe.com (WireMock). */
    StripePaymentGateway(StripeProperties properties, WebhookPayloadParser parser, StripeClient client) {
        this.properties = properties;
        this.parser = parser;
        this.client = client;
        if (client == null) {
            log.warn("stripe_secret_key is not set: payment and refund calls will fail with 502 until a Stripe "
                    + "sandbox key (sk_test_...) is configured");
        }
        if (!properties.hasWebhookSecret()) {
            log.warn("stripe_webhook_secret is not set: Stripe webhooks will be rejected until it is configured");
        }
    }

    @Override
    public GatewayPaymentResult createPaymentIntent(long amountCents, String paymentMethodId,
                                                    String idempotencyKey, Map<String, String> metadata) {
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(CURRENCY)
                .putAllMetadata(metadata)
                // Card-only, no redirects: lets the backend confirm with a test PaymentMethod directly.
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build());
        if (paymentMethodId != null && !paymentMethodId.isBlank()) {
            params.setPaymentMethod(paymentMethodId).setConfirm(true);
        }
        try {
            PaymentIntent intent = stripe().v1().paymentIntents().create(params.build(), options(idempotencyKey));
            return toResult(intent);
        } catch (CardException e) {
            // Declined card: Stripe still created the PaymentIntent, which we keep for reference.
            String intentId = e.getStripeError() != null && e.getStripeError().getPaymentIntent() != null
                    ? e.getStripeError().getPaymentIntent().getId() : null;
            String reason = e.getDeclineCode() != null ? e.getDeclineCode() : e.getCode();
            return GatewayPaymentResult.failed(intentId, "Card declined (" + reason + ")");
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe payment request failed (" + describe(e) + ")", e);
        }
    }

    @Override
    public GatewayPaymentResult retrievePaymentIntent(String paymentIntentId) {
        try {
            return toResult(stripe().v1().paymentIntents().retrieve(paymentIntentId));
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe payment lookup failed (" + describe(e) + ")", e,
                    isRetryable(e), e.getCode());
        }
    }

    @Override
    public GatewayRefundResult refund(String paymentIntentId, long amountCents, String idempotencyKey) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw PaymentGatewayException.permanent("Payment has no Stripe PaymentIntent to refund", null);
        }
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setAmount(amountCents)
                .build();
        try {
            Refund refund = stripe().v1().refunds().create(params, options(idempotencyKey));
            // "succeeded" = money returned; "pending"/"requires_action" = accepted, final result by webhook.
            if ("failed".equals(refund.getStatus()) || "canceled".equals(refund.getStatus())) {
                throw PaymentGatewayException.permanent(
                        "Stripe refund " + refund.getId() + " is " + refund.getStatus(), refund.getFailureReason());
            }
            return new GatewayRefundResult(refund.getId(), refund.getStatus());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe refund request failed (" + describe(e) + ")", e,
                    isRetryable(e), e.getCode());
        }
    }

    @Override
    public List<GatewayRefundResult> listRefunds(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return List.of();
        }
        RefundListParams params = RefundListParams.builder().setPaymentIntent(paymentIntentId).setLimit(20L).build();
        try {
            return stripe().v1().refunds().list(params).getData().stream()
                    .map(refund -> new GatewayRefundResult(refund.getId(), refund.getStatus()))
                    .toList();
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe refund lookup failed (" + describe(e) + ")", e,
                    isRetryable(e), e.getCode());
        }
    }

    @Override
    public GatewayWebhookEvent parseWebhook(String payload, String signatureHeader) {
        // With real Stripe every webhook must be signed: an unsigned one could fake a payment or refund.
        if (!properties.hasWebhookSecret()) {
            throw new InvalidWebhookException(
                    "Stripe webhook signing secret (stripe_webhook_secret) is not configured; webhook rejected");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookException("Missing Stripe-Signature header");
        }
        try {
            Webhook.constructEvent(payload, signatureHeader, properties.webhookSecret());
        } catch (SignatureVerificationException e) {
            throw new InvalidWebhookException("Invalid Stripe webhook signature", e);
        }
        return parser.parse(payload);
    }

    private static StripeClient client(StripeProperties properties, String apiBase) {
        if (!properties.hasSecretKey()) {
            return null;
        }
        if (apiBase == null || apiBase.isBlank()) {
            return new StripeClient(properties.secretKey());
        }
        if (!apiBase.startsWith("http://") && !apiBase.startsWith("https://")) {
            throw new IllegalArgumentException("stripe_api_base must be an http(s) URL");
        }
        log.warn("Stripe API calls go to {} instead of api.stripe.com (test profile)", apiBase);
        return StripeClient.builder().setApiKey(properties.secretKey()).setApiBase(apiBase).build();
    }

    private StripeClient stripe() {
        if (client == null) {
            throw PaymentGatewayException.permanent("Stripe is not configured (stripe_secret_key is empty)", null);
        }
        return client;
    }

    private static RequestOptions options(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static GatewayPaymentResult toResult(PaymentIntent intent) {
        List<String> types = intent.getPaymentMethodTypes();
        String methodType = types == null || types.isEmpty() ? null : types.get(0);
        return switch (intent.getStatus()) {
            case "succeeded" -> GatewayPaymentResult.succeeded(intent.getId(), methodType);
            case "canceled" -> GatewayPaymentResult.failed(intent.getId(), "PaymentIntent was canceled");
            default -> GatewayPaymentResult.pending(intent.getId(), intent.getClientSecret());
        };
    }

    /**
     * Whether trying again can help: network problems, rate limits and Stripe server errors are transient.
     * Invalid requests (e.g. charge_already_refunded), card, authentication, permission and idempotency errors
     * are not. Retrying always reuses the same idempotency key, so a retry can never create a second refund.
     */
    static boolean isRetryable(StripeException e) {
        if (e instanceof RateLimitException || e instanceof ApiConnectionException || e instanceof ApiException) {
            return true;
        }
        Integer status = e.getStatusCode();
        return status != null && status >= 500;
    }

    /** Stripe error code + HTTP status only: never the request, key, or full message. */
    private static String describe(StripeException e) {
        return "code=" + e.getCode() + ", status=" + e.getStatusCode();
    }
}
