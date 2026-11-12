package events.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * Unit tests for {@link PaymentClient} against WireMock, over real HTTP with the client's real request factory and
 * timeouts: the wire format of each call to the payment service, and how its answers and failures (error statuses,
 * timeouts, dropped connections) are translated.
 */
class PaymentClientTest {

    @RegisterExtension
    static final WireMockExtension PAYMENT = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private PaymentClient client;

    @BeforeEach
    void setUp() {
        // Generous timeouts, so a busy machine (or CI runner) never turns a normal answer into a timeout
        client = new PaymentClient(PAYMENT.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5), "service-key");
    }

    @Test
    void createBookingSendsOnePlaceAndReturnsTheBookingId() {
        PAYMENT.stubFor(post(urlEqualTo("/bookings"))
                .withRequestBody(equalToJson("""
                        {"userId": 42, "eventId": 3, "quantity": 1, "totalAmount": 15.00, "eventTitle": "Neon Party"}
                        """))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("""
                        {"bookingId": 7, "userId": 42, "eventId": 3, "quantity": 1, "totalAmount": 15.00,
                         "currency": "SGD", "status": "PENDING"}
                        """)));

        assertThat(client.createBooking(42, 3, 1, new BigDecimal("15.00"), "Neon Party")).isEqualTo(7);
        // Buffered body: a Content-Length, never a chunked request
        PAYMENT.verify(postRequestedFor(urlEqualTo("/bookings"))
                .withHeader("Content-Length", matching("[0-9]+"))
                .withHeader("Transfer-Encoding", absent()));
    }

    @Test
    void createBookingSendsTheOrdersTicketCountAndTotal() {
        PAYMENT.stubFor(post(urlEqualTo("/bookings"))
                .withRequestBody(equalToJson("""
                        {"userId": 42, "eventId": 3, "quantity": 3, "totalAmount": 45.00, "eventTitle": "Neon Party"}
                        """))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("""
                        {"bookingId": 8, "userId": 42, "eventId": 3, "quantity": 3, "totalAmount": 45.00,
                         "currency": "SGD", "status": "PENDING"}
                        """)));

        assertThat(client.createBooking(42, 3, 3, new BigDecimal("45.00"), "Neon Party")).isEqualTo(8);
    }

    @Test
    void createBookingWithoutBookingIdInTheAnswerIsUnavailable() {
        PAYMENT.stubFor(post(urlEqualTo("/bookings")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        assertThatThrownBy(() -> client.createBooking(42, 3, 1, new BigDecimal("15.00"), "Neon Party"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void createPaymentSendsBookingAndMethodAndReadsAPaidPayment() {
        PAYMENT.stubFor(post(urlEqualTo("/payments"))
                .withRequestBody(equalToJson("""
                        {"bookingId": 7, "paymentMethodId": "pm_card_visa"}
                        """))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("""
                        {"paymentId": 5, "bookingId": 7, "amount": 15.00, "currency": "SGD", "status": "PAID",
                         "stripePaymentIntentId": "pi_1", "paidAt": "2026-10-07T14:04:02"}
                        """)));

        PaymentResponse payment = client.createPayment(7, "pm_card_visa");

        assertThat(payment.paymentId()).isEqualTo(5);
        assertThat(payment.isPaid()).isTrue();
        assertThat(payment.isFailed()).isFalse();
    }

    @Test
    void aDeclinedCardIsAnAnswerNotAnException() {
        PAYMENT.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"paymentId": 6, "bookingId": 7, "status": "FAILED", "failureReason": "Your card was declined."}
                        """)));

        PaymentResponse payment = client.createPayment(7, "pm_card_chargeDeclined");

        assertThat(payment.isFailed()).isTrue();
        assertThat(payment.failureReason()).isEqualTo("Your card was declined.");
    }

    @Test
    void anIncompletePaymentIsUnavailable() {
        PAYMENT.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"paymentId\": 6}")));

        assertThatThrownBy(() -> client.createPayment(7, "pm_card_visa"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void aClientErrorIsARejectionCarryingThePaymentServiceMessage() {
        PAYMENT.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"timestamp": "2026-10-07T06:03:11.123Z", "status": 409, "error": "Conflict",
                         "message": "Booking 7 already has a PAID payment", "path": "/payments"}
                        """)));

        assertThatThrownBy(() -> client.createPayment(7, "pm_card_visa"))
                .isInstanceOfSatisfying(PaymentRejectedException.class, rejected -> {
                    assertThat(rejected.getStatusCode()).isEqualTo(409);
                    assertThat(rejected.getMessage()).isEqualTo("Booking 7 already has a PAID payment");
                });
    }

    @Test
    void aClientErrorWithoutAReadableBodyFallsBackToTheStatus() {
        PAYMENT.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.createPayment(7, "pm_card_visa"))
                .isInstanceOfSatisfying(PaymentRejectedException.class, rejected -> {
                    assertThat(rejected.getStatusCode()).isEqualTo(404);
                    assertThat(rejected.getMessage()).isEqualTo("404 NOT_FOUND");
                });
    }

    @Test
    void aServerErrorIsUnavailable() {
        PAYMENT.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> client.createPayment(7, "pm_card_visa"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void anAnswerSlowerThanTheReadTimeoutIsUnavailableAndNamesTheRootCause() {
        PAYMENT.stubFor(post(urlEqualTo("/payments")).willReturn(okJson("{}").withFixedDelay(2000)));
        PaymentClient impatient = new PaymentClient(PAYMENT.baseUrl(), Duration.ofSeconds(5),
                Duration.ofMillis(300), "service-key");

        assertThatThrownBy(() -> impatient.createPayment(7, "pm_card_visa"))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessageContaining("SocketTimeoutException");
    }

    @Test
    void aDroppedConnectionIsUnavailable() {
        PAYMENT.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertThatThrownBy(() -> client.createPayment(7, "pm_card_visa"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void anUnreachablePaymentServiceIsUnavailable() {
        PaymentClient unreachable = new PaymentClient("http://localhost:1", Duration.ofMillis(500),
                Duration.ofMillis(500), "service-key");

        assertThatThrownBy(() -> unreachable.findPayments(7)).isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void findPaymentsReturnsEveryPaymentOfTheBooking() {
        PAYMENT.stubFor(get(urlEqualTo("/payments/booking/7")).willReturn(okJson("""
                [{"paymentId": 4, "bookingId": 7, "status": "FAILED"},
                 {"paymentId": 5, "bookingId": 7, "status": "PAID"}]
                """)));

        assertThat(client.findPayments(7))
                .extracting(PaymentResponse::paymentId, PaymentResponse::status)
                .containsExactly(Tuple.tuple(4, "FAILED"), Tuple.tuple(5, "PAID"));
    }

    @Test
    void refundPostsToTheRefundEndpointWithTheServiceKey() {
        PAYMENT.stubFor(post(urlEqualTo("/payments/5/refund")).willReturn(okJson("""
                {"paymentId": 5, "outcome": "REFUNDED", "paymentStatus": "REFUNDED",
                 "stripeRefundId": "re_1", "attempts": 1, "message": "Refunded"}
                """)));

        RefundResponse refund = client.refund(5);

        assertThat(refund.outcome()).isEqualTo("REFUNDED");
        PAYMENT.verify(postRequestedFor(urlEqualTo("/payments/5/refund"))
                .withHeader("X-Service-Api-Key", equalTo("service-key")));
    }

    @Test
    void withoutAServiceKeyNoKeyHeaderIsSent() {
        PAYMENT.stubFor(post(urlEqualTo("/payments/5/refund")).willReturn(aResponse().withStatus(401)));
        PaymentClient withoutKey = new PaymentClient(PAYMENT.baseUrl(), Duration.ofSeconds(5),
                Duration.ofSeconds(5), " ");

        assertThatThrownBy(() -> withoutKey.refund(5))
                .isInstanceOfSatisfying(PaymentRejectedException.class,
                        rejected -> assertThat(rejected.getStatusCode()).isEqualTo(401));
        PAYMENT.verify(postRequestedFor(urlEqualTo("/payments/5/refund"))
                .withHeader("X-Service-Api-Key", absent()));
    }

    @Test
    void anEmptyRefundAnswerIsUnavailable() {
        PAYMENT.stubFor(post(urlEqualTo("/payments/5/refund")).willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.refund(5)).isInstanceOf(PaymentUnavailableException.class);
    }
}
