package events.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

/**
 * Test-specific WireMock stubs of the payment service, layered over the default stubs in
 * {@code src/test/resources/wiremock/payment} (priority {@value #OVERRIDE} beats their 5 and 9).
 *
 * <p>The stubs keep no state. Where the payment service's answer depends on what happened before (a charge or a
 * refund whose answer was lost), the stubs form a WireMock scenario: a small state machine that the requests
 * themselves move on, so the "after" answers only appear once the "before" request has really arrived.
 */
final class PaymentStubs {

    static final int OVERRIDE = 1;

    private PaymentStubs() {
    }

    // The payment service's JSON error body (its ErrorResponse)
    static ResponseDefinitionBuilder error(int status, String message) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\": %d, \"error\": \"error\", \"message\": \"%s\", \"path\": \"/\"}"
                        .formatted(status, message));
    }

    // POST /payments answered PAID for this payment-side booking (the payment id is the booking id, as in the
    // default stubs)
    static ResponseDefinitionBuilder paid(int bookingId) {
        return aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\": %d, \"bookingId\": %d, \"currency\": \"SGD\", \"status\": \"PAID\"}"
                        .formatted(bookingId, bookingId));
    }

    // POST /payments/{paymentId}/refund answered REFUNDED
    static ResponseDefinitionBuilder refunded(int paymentId) {
        return okJson("{\"paymentId\": %d, \"outcome\": \"REFUNDED\", \"paymentStatus\": \"REFUNDED\", \"attempts\": 1}"
                .formatted(paymentId));
    }

    // GET /payments/booking/{bookingId} listing its one payment in the given status
    static ResponseDefinitionBuilder paymentsOfBooking(int bookingId, String status) {
        return okJson("[{\"paymentId\": %d, \"bookingId\": %d, \"status\": \"%s\"}]"
                .formatted(bookingId, bookingId, status));
    }

    // The card of this payment-side booking is charged, but the caller gets lostAnswer instead of the result (a
    // fault, a delay past the read timeout, or an error status). From then on the payment service knows it was paid:
    // another POST /payments is a 409, and GET /payments/booking/{bookingId} lists the PAID payment.
    static void chargedButAnswerLost(WireMockServer payment, int bookingId, ResponseDefinitionBuilder lostAnswer) {
        String scenario = "charge of booking " + bookingId;
        payment.stubFor(post(urlEqualTo("/payments"))
                .withRequestBody(matchingJsonPath("$.bookingId", equalTo(String.valueOf(bookingId))))
                .atPriority(OVERRIDE)
                .inScenario(scenario).whenScenarioStateIs(STARTED)
                .willReturn(lostAnswer)
                .willSetStateTo("charged"));
        payment.stubFor(post(urlEqualTo("/payments"))
                .atPriority(OVERRIDE)
                .inScenario(scenario).whenScenarioStateIs("charged")
                .willReturn(error(409, "Booking " + bookingId + " already has a pending or completed payment")));
        payment.stubFor(get(urlEqualTo("/payments/booking/" + bookingId))
                .atPriority(OVERRIDE)
                .inScenario(scenario).whenScenarioStateIs("charged")
                .willReturn(paymentsOfBooking(bookingId, "PAID")));
    }

    // The refund of this payment gets refundAnswer, and from then on GET /payments/booking/{bookingId} shows the
    // payment in stateAfterwards: REFUNDED for a refund whose answer was lost, REFUND_FAILED when the payment service
    // gave up on Stripe (its 502) and moved the refund to its own refund DLQ
    static void refundAnswered(WireMockServer payment, int paymentId, ResponseDefinitionBuilder refundAnswer,
            String stateAfterwards) {
        String scenario = "refund of payment " + paymentId;
        payment.stubFor(post(urlEqualTo("/payments/" + paymentId + "/refund"))
                .atPriority(OVERRIDE)
                .inScenario(scenario).whenScenarioStateIs(STARTED)
                .willReturn(refundAnswer)
                .willSetStateTo("refund handled"));
        payment.stubFor(get(urlEqualTo("/payments/booking/" + paymentId))
                .atPriority(OVERRIDE)
                .inScenario(scenario).whenScenarioStateIs("refund handled")
                .willReturn(paymentsOfBooking(paymentId, stateAfterwards)));
    }
}
