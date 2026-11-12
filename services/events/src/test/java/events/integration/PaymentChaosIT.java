package events.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static events.integration.PaymentStubs.OVERRIDE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

import tools.jackson.databind.JsonNode;

/**
 * Chaos experiments on the payment side (JUnit tag {@code chaos}: {@code mvnw verify -Pchaos}, CI job chaos-tests).
 *
 * <p>Each experiment breaks the payment service in one specific way at the worst moment (a network fault, an answer
 * slower than the 3 s read timeout, an error status, or the server gone altogether) and checks the property that
 * matters: no money is left behind. Every charge ends as a CONFIRMED booking or as a refund the payment service has
 * taken over, with no one retrying by hand: only the payment recovery job, run here on demand.
 */
@Tag("chaos")
class PaymentChaosIT extends IntegrationTestBase {

    // ---------- refunds (saga compensation) ----------

    @Test
    void aRefundOwedWhileThePaymentServiceIsDownIsMadeOnceItIsBack() {
        // The payment service starts failing just as the refund is needed, then goes away completely
        StubMapping failing = PAYMENT.stubFor(WireMock.post(urlEqualTo("/payments/42/refund"))
                .atPriority(OVERRIDE)
                .willReturn(PaymentStubs.error(503, "Service Unavailable")));
        int bookingId = bookingChargedAfterItsEventWasCancelled();
        assertRefundOwed(bookingId);

        PAYMENT.stop();
        scheduler.recoverPayments();  // connection refused
        assertRefundOwed(bookingId);

        PAYMENT.start();
        PAYMENT.removeStub(failing);
        scheduler.recoverPayments();

        assertRefundSettled(bookingId, "REFUNDED");
        PAYMENT.verify(2, postRequestedFor(urlEqualTo("/payments/42/refund"))
                .withHeader("X-Service-Api-Key", equalTo(SERVICE_API_KEY)));
    }

    @Test
    void aRefundWhoseAnswerIsLostIsNotMadeTwice() {
        // The refund goes through, but its answer arrives after the 3 s read timeout
        PaymentStubs.refundAnswered(PAYMENT, 42, PaymentStubs.refunded(42).withFixedDelay(5000), "REFUNDED");
        int bookingId = bookingChargedAfterItsEventWasCancelled();
        assertRefundOwed(bookingId);

        scheduler.recoverPayments();

        // The retry looks the payment up first, finds it REFUNDED and records that, without refunding again
        assertRefundSettled(bookingId, "REFUNDED");
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/payments/42/refund")));
    }

    @Test
    void aRefundThePaymentServiceGaveUpOnIsLeftToItsRefundDeadLetterQueue() {
        // Stripe keeps failing: the payment service marks the payment REFUND_FAILED, puts it in its refund DLQ for an
        // admin, raises an alert and answers 502
        PaymentStubs.refundAnswered(PAYMENT, 42, PaymentStubs.error(502, "Refund for payment 42 failed after 3 "
                + "attempts; it is marked REFUND_FAILED and was sent to the refund dead-letter queue"), "REFUND_FAILED");
        int bookingId = bookingChargedAfterItsEventWasCancelled();
        assertRefundOwed(bookingId);

        scheduler.recoverPayments();
        scheduler.recoverPayments();

        // The payment service's admin recovery owns it now: no new Stripe round, and no new alert, every minute
        assertRefundSettled(bookingId, "REFUND_FAILED");
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/payments/42/refund")));
    }

    @Test
    void aRefusedServiceKeyKeepsTheRefundOwedUntilThePaymentServiceAcceptsIt() {
        // The two services' keys do not match (a deployment mistake): every refund is refused with 401
        StubMapping keyMismatch = PAYMENT.stubFor(WireMock.post(urlMatching("/payments/[0-9]+/refund"))
                .atPriority(OVERRIDE)
                .willReturn(PaymentStubs.error(401, "Missing or invalid credentials")));
        int bookingId = bookingChargedAfterItsEventWasCancelled();

        scheduler.recoverPayments();
        scheduler.recoverPayments();
        assertRefundOwed(bookingId);  // a refusal is never mistaken for "done"

        PAYMENT.removeStub(keyMismatch);  // the key is fixed
        scheduler.recoverPayments();

        assertRefundSettled(bookingId, "REFUNDED");
        PAYMENT.verify(4, postRequestedFor(urlEqualTo("/payments/42/refund")));
    }

    // ---------- charges whose answer is lost ----------

    // Ways the answer to a charge can be lost after the card was charged
    enum LostAnswer {
        TIMEOUT(PaymentStubs.paid(42).withFixedDelay(5000)),  // slower than the 3 s read timeout
        CONNECTION_RESET(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        EMPTY_RESPONSE(aResponse().withFault(Fault.EMPTY_RESPONSE)),
        GARBLED_RESPONSE(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)),
        BAD_GATEWAY(PaymentStubs.error(502, "Bad Gateway"));  // e.g. a load balancer that lost the connection

        private final ResponseDefinitionBuilder response;

        LostAnswer(ResponseDefinitionBuilder response) {
            this.response = response;
        }
    }

    @ParameterizedTest
    @EnumSource
    void aChargeWhoseAnswerIsLostIsConfirmedWithoutTheUserRetrying(LostAnswer lostAnswer) {
        int eventId = createEvent(5, "15.00");
        PaymentStubs.chargedButAnswerLost(PAYMENT, 42, lostAnswer.response);

        Response signup = signUp(eventId, 42, "pm_card_visa");
        assertThat(signup.status()).isEqualTo(201);  // the place is held, whatever happened to the answer
        int bookingId = signup.body().path("id").asInt();

        scheduler.recoverPayments();

        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("payment_reference")).isEqualTo("42");
        assertThat(row.get("refund_required_at")).isNull();
        assertThat(capacity(eventId)).isEqualTo(4);
        assertThat(signupNotificationIds()).contains("signup-booking-" + bookingId);  // the "You're in!" email
    }

    @Test
    void aPaymentServiceThatIsDownChargesNothingAndHoldsThePlaceUntilItIsBack() {
        int eventId = createEvent(5, "15.00");
        PAYMENT.stop();

        Response signup = signUp(eventId, 42, "pm_card_visa");
        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.body().path("status").asString()).isEqualTo("PENDING");
        assertThat(signup.body().path("paymentBookingId").isNull()).isTrue();  // nothing reached it, nothing charged
        int bookingId = signup.body().path("id").asInt();
        scheduler.recoverPayments();  // nothing to recover

        PAYMENT.start();
        Response retry = pay(eventId, bookingId, "pm_card_visa");

        assertThat(retry.body().path("status").asString()).isEqualTo("CONFIRMED");
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/payments")));
    }

    // ---------- helpers ----------

    // User 42 signs up for a paid event and, while the card is being charged (payment-side booking and payment 42),
    // the organiser cancels the event: the charge has to be refunded. Returns the booking id.
    private int bookingChargedAfterItsEventWasCancelled() {
        int eventId = createEvent(5, "15.00");
        PAYMENT.stubFor(WireMock.post(urlEqualTo("/payments")).atPriority(OVERRIDE)
                .willReturn(PaymentStubs.paid(42).withFixedDelay(1000)));

        CompletableFuture<Response> signup = CompletableFuture.supplyAsync(() -> signUp(eventId, 42, "pm_card_visa"));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !PAYMENT.findAll(postRequestedFor(urlEqualTo("/payments"))).isEmpty());
        assertThat(post("/events/" + eventId + "/cancel").status()).isEqualTo(200);
        Response response = signup.join();

        assertThat(response.body().path("status").asString()).isEqualTo("CANCELLED");
        return response.body().path("id").asInt();
    }

    private void assertRefundOwed(int bookingId) {
        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("payment_reference")).isEqualTo("42");
        assertThat(row.get("refund_required_at")).isNotNull();
        assertThat(row.get("refunded_at")).isNull();
    }

    private void assertRefundSettled(int bookingId, String reference) {
        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("refunded_at")).isNotNull();
        assertThat(row.get("refund_reference")).isEqualTo(reference);
    }

    // The notification_id of every notify.signup message published so far
    private List<String> signupNotificationIds() {
        List<String> ids = new ArrayList<>();
        for (JsonNode message = receiveJson(SIGNUP_QUEUE, 2000); message != null;
                message = receiveJson(SIGNUP_QUEUE, 500)) {
            ids.add(message.path("notification_id").asString());
        }
        return ids;
    }
}
