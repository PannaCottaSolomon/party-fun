package payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.net.Webhook;

import payment.config.StripeProperties;
import payment.exception.InvalidWebhookException;
import payment.exception.PaymentGatewayException;
import tools.jackson.databind.json.JsonMapper;

/** Gateway tests that never reach Stripe's servers. */
class GatewayTest {

    private static final String SECRET = "whsec_unit_test_secret";
    private static final String PAYLOAD = """
            {"id":"evt_1","type":"payment_intent.succeeded",
             "data":{"object":{"id":"pi_1","object":"payment_intent","status":"succeeded",
                               "payment_method_types":["card"]}}}""";

    private final WebhookPayloadParser parser = new WebhookPayloadParser(JsonMapper.builder().build());

    private static String sign(String payload, String secret) throws Exception {
        long timestamp = Webhook.Util.getTimeNow();
        String signature = Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);
        return "t=" + timestamp + ",v1=" + signature;
    }

    @Test
    void validStripeSignatureIsAccepted() throws Exception {
        StripePaymentGateway gateway = new StripePaymentGateway(new StripeProperties(null, SECRET, null), parser);

        GatewayWebhookEvent event = gateway.parseWebhook(PAYLOAD, sign(PAYLOAD, SECRET));

        assertThat(event.type()).isEqualTo("payment_intent.succeeded");
        assertThat(event.paymentIntentId()).isEqualTo("pi_1");
        assertThat(event.paymentMethodType()).isEqualTo("card");
    }

    @Test
    void wrongStripeSignatureIsRejected() throws Exception {
        StripePaymentGateway gateway = new StripePaymentGateway(new StripeProperties(null, SECRET, null), parser);

        assertThatThrownBy(() -> gateway.parseWebhook(PAYLOAD, sign(PAYLOAD, "whsec_someone_else")))
                .isInstanceOf(InvalidWebhookException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(PAYLOAD, null))
                .isInstanceOf(InvalidWebhookException.class)
                .hasMessageContaining("Stripe-Signature");
    }

    @Test
    void stripeGatewayWithoutKeyFailsFastInsteadOfCallingStripe() {
        StripePaymentGateway gateway = new StripePaymentGateway(new StripeProperties("", "", null), parser);

        assertThatThrownBy(() -> gateway.createPaymentIntent(100, "pm_card_visa", "k", Map.of()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> gateway.refund("pi_1", 100, "k"))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void stripePropertiesNeverPrintSecrets() {
        assertThat(new StripeProperties("sk_test_abc", SECRET, null).toString())
                .doesNotContain("sk_test_abc")
                .doesNotContain(SECRET);
    }

    @Test
    void parserReadsRefundEvents() {
        GatewayWebhookEvent refund = parser.parse("""
                {"id":"evt_2","type":"refund.updated",
                 "data":{"object":{"id":"re_1","object":"refund","status":"succeeded","payment_intent":"pi_1"}}}""");
        GatewayWebhookEvent charge = parser.parse("""
                {"id":"evt_3","type":"charge.refunded",
                 "data":{"object":{"id":"ch_1","object":"charge","refunded":true,"payment_intent":"pi_1"}}}""");

        assertThat(refund.refundId()).isEqualTo("re_1");
        assertThat(refund.refundStatus()).isEqualTo("succeeded");
        assertThat(refund.paymentIntentId()).isEqualTo("pi_1");
        assertThat(charge.refundStatus()).isEqualTo("succeeded");
        assertThat(charge.paymentIntentId()).isEqualTo("pi_1");
    }

    @Test
    void parserRejectsGarbage() {
        assertThatThrownBy(() -> parser.parse("nope")).isInstanceOf(InvalidWebhookException.class);
        assertThatThrownBy(() -> parser.parse("{\"id\":\"evt\"}")).isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    void onlySandboxSecretKeysAreAccepted() {
        assertThat(new StripeProperties("sk_test_abc", SECRET, null).hasSecretKey()).isTrue();
        assertThat(new StripeProperties("rk_test_abc", SECRET, null).hasSecretKey()).isTrue();
        assertThat(new StripeProperties("", "", null).hasSecretKey()).isFalse();
        assertThat(new StripeProperties(null, null, null).hasSecretKey()).isFalse();
    }

    @Test
    void liveAndPublishableKeysStopTheServiceWithoutLeakingThem() {
        for (String key : new String[] {"sk_live_abc123", "rk_live_abc123", "whatever123"}) {
            assertThatThrownBy(() -> new StripeProperties(key, SECRET, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sandbox")
                    .hasMessageNotContaining(key);
        }
        assertThatThrownBy(() -> new StripeProperties("pk_test_abc123", SECRET, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publishable")
                .hasMessageNotContaining("pk_test_abc123");
    }

    @Test
    void onlySandboxPublishableKeysAreAcceptedAndSecretsAreNeverServedAsOne() {
        assertThat(new StripeProperties(null, SECRET, "pk_test_abc").publishableKey()).isEqualTo("pk_test_abc");
        assertThat(new StripeProperties(null, SECRET, "").toString()).contains("publishableKey=<unset>");
        for (String key : new String[] {"pk_live_abc123", "sk_test_abc123", "rk_test_abc123", "whatever123"}) {
            assertThatThrownBy(() -> new StripeProperties(null, SECRET, key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stripe_publishable_key")
                    .hasMessageNotContaining(key);
        }
        assertThatThrownBy(() -> new StripeProperties(null, SECRET, "sk_test_abc123"))
                .hasMessageContaining("secret key");
    }

    @Test
    void liveModeWebhooksAreRejected() throws Exception {
        String live = PAYLOAD.replace("\"type\"", "\"livemode\":true,\"type\"");
        StripePaymentGateway gateway = new StripePaymentGateway(new StripeProperties(null, SECRET, null), parser);

        assertThatThrownBy(() -> gateway.parseWebhook(live, sign(live, SECRET)))
                .isInstanceOf(InvalidWebhookException.class)
                .hasMessageContaining("Live-mode");
        assertThat(parser.parse(PAYLOAD.replace("\"type\"", "\"livemode\":false,\"type\"")).paymentIntentId())
                .isEqualTo("pi_1");
    }

    @Test
    void moneyConvertsSgdToCents() {
        assertThat(Money.toCents(new BigDecimal("40.00"))).isEqualTo(4000);
        assertThat(Money.toCents(new BigDecimal("0.5"))).isEqualTo(50);
        assertThatThrownBy(() -> Money.toCents(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void unsignedWebhooksAreRejectedWhenNoSigningSecretIsConfigured() {
        StripePaymentGateway gateway = new StripePaymentGateway(new StripeProperties("sk_test_x", "", null), parser);

        assertThatThrownBy(() -> gateway.parseWebhook(PAYLOAD, null))
                .isInstanceOf(InvalidWebhookException.class)
                .hasMessageContaining("stripe_webhook_secret");
    }

    @Test
    void parserReadsRefundFailedAndChargeRefundUpdated() {
        GatewayWebhookEvent failed = parser.parse("""
                {"id":"evt_f","type":"refund.failed",
                 "data":{"object":{"id":"re_1","object":"refund","status":"failed","payment_intent":"pi_1"}}}""");
        GatewayWebhookEvent updated = parser.parse("""
                {"id":"evt_u","type":"charge.refund.updated",
                 "data":{"object":{"id":"re_2","object":"refund","status":"succeeded","payment_intent":"pi_2"}}}""");

        assertThat(failed.refundStatus()).isEqualTo("failed");
        assertThat(failed.paymentIntentId()).isEqualTo("pi_1");
        assertThat(updated.refundId()).isEqualTo("re_2");
        assertThat(updated.refundStatus()).isEqualTo("succeeded");
    }

    @Test
    void transientStripeErrorsAreRetryablePermanentOnesAreNot() {
        assertThat(StripePaymentGateway.isRetryable(new ApiConnectionException("timeout"))).isTrue();
        assertThat(StripePaymentGateway.isRetryable(
                new RateLimitException("slow down", null, "req", "rate_limit", 429, null))).isTrue();
        assertThat(StripePaymentGateway.isRetryable(new ApiException("boom", "req", null, 500, null))).isTrue();
        assertThat(StripePaymentGateway.isRetryable(new InvalidRequestException("already refunded", "charge", "req",
                "charge_already_refunded", 400, null))).isFalse();
    }
}
