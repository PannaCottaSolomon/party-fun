package payment.stripe;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.stripe.StripeClient;

import payment.config.StripeProperties;
import payment.exception.PaymentGatewayException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Stripe adapter against WireMock standing in for api.stripe.com: the requests it sends (amount in cents, SGD,
 * idempotency key) and how it maps Stripe's answers, including the failures the sandbox can't produce on demand
 * (5xx, rate limits, timeouts, dropped connections). Never reaches Stripe; the integration tests use the real sandbox.
 */
class StripePaymentGatewayWireMockTest {

    private static final String KEY = "sk_test_wiremock";
    private static final int READ_TIMEOUT_MS = 500;

    @RegisterExtension
    static final WireMockExtension STRIPE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private StripePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        StripeClient client = StripeClient.builder()
                .setApiKey(KEY)
                .setApiBase(STRIPE.baseUrl())
                .setReadTimeout(READ_TIMEOUT_MS)
                .setMaxNetworkRetries(0)
                .build();
        gateway = new StripePaymentGateway(new StripeProperties(KEY, null, null),
                new WebhookPayloadParser(JsonMapper.builder().build()), client);
    }

    private static void stubJson(String method, String path, int status, String body) {
        var response = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
        STRIPE.stubFor("GET".equals(method) ? get(urlPathEqualTo(path)).willReturn(response)
                : post(urlPathEqualTo(path)).willReturn(response));
    }

    private static String paymentIntent(String id, String status) {
        return """
                {"id":"%s","object":"payment_intent","status":"%s","amount":2500,"currency":"sgd",
                 "client_secret":"%s_secret_x","payment_method_types":["card"]}""".formatted(id, status, id);
    }

    private static String error(String type, String code, String message) {
        return """
                {"error":{"type":"%s","code":"%s","message":"%s"}}""".formatted(type, code, message);
    }

    private PaymentGatewayException refundFailure() {
        return catchThrowableOfType(PaymentGatewayException.class, () -> gateway.refund("pi_1", 2500, "refund-7-1"));
    }

    // ---------------------------------------------------------------- what the adapter sends

    @Test
    void paymentRequestCarriesAmountInCentsSgdMetadataAndIdempotencyKey() {
        stubJson("POST", "/v1/payment_intents", 200, paymentIntent("pi_1", "succeeded"));

        gateway.createPaymentIntent(2500, "pm_card_visa", "pay-7", Map.of("bookingId", "7"));

        STRIPE.verify(postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Authorization", equalTo("Bearer " + KEY))
                .withHeader("Idempotency-Key", equalTo("pay-7"))
                .withFormParam("amount", equalTo("2500"))
                .withFormParam("currency", equalTo("sgd"))
                .withFormParam("payment_method", equalTo("pm_card_visa"))
                .withFormParam("confirm", equalTo("true"))
                .withFormParam("metadata[bookingId]", equalTo("7"))
                .withFormParam("automatic_payment_methods[enabled]", equalTo("true"))
                .withFormParam("automatic_payment_methods[allow_redirects]", equalTo("never")));
    }

    @Test
    void paymentWithoutPaymentMethodIsNotConfirmed() {
        stubJson("POST", "/v1/payment_intents", 200, paymentIntent("pi_1", "requires_payment_method"));

        gateway.createPaymentIntent(2500, null, "pay-8", Map.of());

        STRIPE.verify(postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withFormParam("payment_method", absent())
                .withFormParam("confirm", absent()));
    }

    @Test
    void refundRequestCarriesPaymentIntentAmountAndIdempotencyKey() {
        stubJson("POST", "/v1/refunds", 200, """
                {"id":"re_1","object":"refund","status":"succeeded","amount":2500}""");

        gateway.refund("pi_1", 2500, "refund-7-1");

        STRIPE.verify(postRequestedFor(urlEqualTo("/v1/refunds"))
                .withHeader("Idempotency-Key", equalTo("refund-7-1"))
                .withFormParam("payment_intent", equalTo("pi_1"))
                .withFormParam("amount", equalTo("2500")));
    }

    @Test
    void listRefundsAsksForThePaymentIntentsRefunds() {
        stubJson("GET", "/v1/refunds", 200, """
                {"object":"list","url":"/v1/refunds","has_more":false,
                 "data":[{"id":"re_1","object":"refund","status":"succeeded"},
                         {"id":"re_2","object":"refund","status":"pending"}]}""");

        List<GatewayRefundResult> refunds = gateway.listRefunds("pi_1");

        assertThat(refunds).extracting(GatewayRefundResult::refundId).containsExactly("re_1", "re_2");
        STRIPE.verify(getRequestedFor(urlPathEqualTo("/v1/refunds"))
                .withQueryParam("payment_intent", equalTo("pi_1"))
                .withQueryParam("limit", equalTo("20")));
    }

    // ---------------------------------------------------------------- how Stripe's answers are mapped

    @Test
    void succeededPaymentIntentIsSucceededWithItsMethodType() {
        stubJson("POST", "/v1/payment_intents", 200, paymentIntent("pi_1", "succeeded"));

        GatewayPaymentResult result = gateway.createPaymentIntent(2500, "pm_card_visa", "pay-7", Map.of());

        assertThat(result.status()).isEqualTo(GatewayPaymentResult.Status.SUCCEEDED);
        assertThat(result.paymentIntentId()).isEqualTo("pi_1");
        assertThat(result.paymentMethodType()).isEqualTo("card");
    }

    @Test
    void paymentIntentNeedingActionIsPendingWithClientSecret() {
        stubJson("POST", "/v1/payment_intents", 200, paymentIntent("pi_2", "requires_action"));

        GatewayPaymentResult result = gateway.createPaymentIntent(2500, "pm_card_threeDSecure2Required", "pay-9", Map.of());

        assertThat(result.status()).isEqualTo(GatewayPaymentResult.Status.PENDING);
        assertThat(result.clientSecret()).isEqualTo("pi_2_secret_x");
    }

    @Test
    void canceledPaymentIntentIsFailed() {
        stubJson("GET", "/v1/payment_intents/pi_3", 200, paymentIntent("pi_3", "canceled"));

        assertThat(gateway.retrievePaymentIntent("pi_3").status()).isEqualTo(GatewayPaymentResult.Status.FAILED);
    }

    @Test
    void declinedCardIsFailedAndKeepsThePaymentIntent() {
        stubJson("POST", "/v1/payment_intents", 402, """
                {"error":{"type":"card_error","code":"card_declined","decline_code":"insufficient_funds",
                 "message":"Your card has insufficient funds.",
                 "payment_intent":{"id":"pi_dec","object":"payment_intent","status":"requires_payment_method"}}}""");

        GatewayPaymentResult result = gateway.createPaymentIntent(2500, "pm_card_visa_chargeDeclined", "pay-10", Map.of());

        assertThat(result.status()).isEqualTo(GatewayPaymentResult.Status.FAILED);
        assertThat(result.paymentIntentId()).isEqualTo("pi_dec");
        assertThat(result.failureReason()).isEqualTo("Card declined (insufficient_funds)");
    }

    @Test
    void pendingRefundIsAcceptedButInProgress() {
        stubJson("POST", "/v1/refunds", 200, """
                {"id":"re_p","object":"refund","status":"pending"}""");

        GatewayRefundResult result = gateway.refund("pi_1", 2500, "refund-7-1");

        assertThat(result.refundId()).isEqualTo("re_p");
        assertThat(result.inProgress()).isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void refundStripeReportsAsFailedIsAPermanentFailure() {
        stubJson("POST", "/v1/refunds", 200, """
                {"id":"re_f","object":"refund","status":"failed","failure_reason":"expired_or_canceled_card"}""");

        PaymentGatewayException failure = refundFailure();

        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.getErrorCode()).isEqualTo("expired_or_canceled_card");
    }

    // ---------------------------------------------------------------- errors: retry or not

    @Test
    void alreadyRefundedChargeIsPermanentAndKeepsStripesCode() {
        stubJson("POST", "/v1/refunds", 400,
                error("invalid_request_error", "charge_already_refunded", "Charge ch_1 has already been refunded."));

        PaymentGatewayException failure = refundFailure();

        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.getErrorCode()).isEqualTo("charge_already_refunded");
    }

    @Test
    void rateLimitIsRetryable() {
        stubJson("POST", "/v1/refunds", 429, error("invalid_request_error", "rate_limit", "Too many requests"));

        assertThat(refundFailure().isRetryable()).isTrue();
    }

    @Test
    void stripeServerErrorsAreRetryable() {
        stubJson("POST", "/v1/refunds", 500, error("api_error", "api_error", "Something went wrong on Stripe's end."));
        assertThat(refundFailure().isRetryable()).isTrue();

        STRIPE.resetAll();
        stubJson("POST", "/v1/refunds", 503, error("api_error", "api_error", "Service unavailable."));
        assertThat(refundFailure().isRetryable()).isTrue();
    }

    @Test
    void authenticationErrorIsPermanent() {
        stubJson("POST", "/v1/refunds", 401, error("invalid_request_error", "api_key_expired", "Expired API Key provided."));

        assertThat(refundFailure().isRetryable()).isFalse();
    }

    @Test
    void responseSlowerThanTheReadTimeoutIsRetryable() {
        STRIPE.stubFor(post(urlPathEqualTo("/v1/refunds")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"succeeded\"}")
                .withFixedDelay(READ_TIMEOUT_MS * 4)));

        assertThat(refundFailure().isRetryable()).isTrue();
    }

    @Test
    void droppedConnectionIsRetryable() {
        STRIPE.stubFor(post(urlPathEqualTo("/v1/refunds"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(refundFailure().isRetryable()).isTrue();
    }

    @Test
    void errorMessagesNeverContainStripesTextOrTheKey() {
        stubJson("POST", "/v1/payment_intents", 500,
                error("api_error", "api_error", "internal detail customer@example.com"));

        PaymentGatewayException failure = catchThrowableOfType(PaymentGatewayException.class,
                () -> gateway.createPaymentIntent(2500, "pm_card_visa", "pay-11", Map.of()));

        assertThat(failure.getMessage()).contains("status=500").doesNotContain("customer@example.com").doesNotContain(KEY);
    }
}
