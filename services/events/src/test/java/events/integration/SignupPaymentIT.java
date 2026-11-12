package events.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static events.integration.PaymentStubs.OVERRIDE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

import tools.jackson.databind.JsonNode;

/**
 * Integration tests of the signup saga: reservation under a real row lock in PostgreSQL, the HTTP calls to the
 * payment service (WireMock), payment retries, reconciliation of lost answers, outages and compensation.
 * Harsher failures (network faults, the payment service going away mid-refund, the broker down) are the chaos
 * tests' job ({@code *ChaosIT}).
 */
class SignupPaymentIT extends IntegrationTestBase {

    @Test
    void aPaidSignupIsConfirmedAndLinkedToThePaymentBooking() {
        int eventId = createEvent(5, "15.00");

        Response signup = signUp(eventId, 42, "pm_card_visa");

        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.body().path("status").asString()).isEqualTo("CONFIRMED");
        int bookingId = signup.body().path("id").asInt();
        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("payment_booking_id")).isEqualTo(42);  // the stub's bookingId is the userId
        assertThat(row.get("payment_reference")).isEqualTo("42");
        assertThat(capacity(eventId)).isEqualTo(4);

        PAYMENT.verify(postRequestedFor(urlEqualTo("/bookings")).withRequestBody(equalToJson("""
                {"userId": 42, "eventId": %d, "quantity": 1, "totalAmount": 15.00, "eventTitle": "IT event"}
                """.formatted(eventId))));
        PAYMENT.verify(postRequestedFor(urlEqualTo("/payments")).withRequestBody(equalToJson("""
                {"bookingId": 42, "paymentMethodId": "pm_card_visa"}
                """)));

        // The Notification Service's contract: the user's id (never an email address), amounts in cents
        JsonNode notification = receiveJson(SIGNUP_QUEUE, 5000);
        assertThat(notification.path("notification_id").asString()).isEqualTo("signup-booking-" + bookingId);
        assertThat(notification.path("user_id").asInt()).isEqualTo(42);
        assertThat(notification.path("registration_id").asInt()).isEqualTo(bookingId);
        assertThat(notification.path("amount").asInt()).isEqualTo(1500);
        assertThat(notification.path("currency").asString()).isEqualTo("SGD");
        assertThat(notification.has("email")).isFalse();
    }

    @Test
    void aSignupForSeveralTicketsCreatesOneBookingPerTicketAndChargesTheOrderOnce() {
        int eventId = createEvent(5, "15.00");

        Response signup = signUp(eventId, 42, "pm_card_visa", 3);

        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.body().isArray()).isTrue();
        assertThat(signup.body().size()).isEqualTo(3);
        List<Integer> bookingIds = new ArrayList<>();
        for (JsonNode booking : signup.body()) {
            assertThat(booking.path("status").asString()).isEqualTo("CONFIRMED");
            assertThat(booking.path("userId").asInt()).isEqualTo(42);
            assertThat(booking.path("paymentReference").asString()).isEqualTo("42");  // the same payment for all
            bookingIds.add(booking.path("id").asInt());
        }
        assertThat(bookingIds).doesNotHaveDuplicates();
        assertThat(capacity(eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT order_id) FROM bookings WHERE status = 'CONFIRMED'",
                Integer.class)).isEqualTo(1);

        // One payment-side booking for the 3 tickets, charged once for their total
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/bookings")));
        PAYMENT.verify(postRequestedFor(urlEqualTo("/bookings")).withRequestBody(equalToJson("""
                {"userId": 42, "eventId": %d, "quantity": 3, "totalAmount": 45.00, "eventTitle": "IT event"}
                """.formatted(eventId))));
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/payments")));

        // One "You're in!" email for the order, with the total
        JsonNode notification = receiveJson(SIGNUP_QUEUE, 5000);
        assertThat(notification.path("registration_id").asInt()).isEqualTo(bookingIds.get(0));
        assertThat(notification.path("amount").asInt()).isEqualTo(4500);
        assertThat(receiveJson(SIGNUP_QUEUE, 1000)).isNull();
    }

    @Test
    void aDeclinedOrderKeepsEveryTicketPendingAndOneRetryPaysThemAll() {
        int eventId = createEvent(5, "15.00");

        Response declined = signUp(eventId, 42, "pm_card_chargeDeclined", 2);
        assertThat(declined.status()).isEqualTo(201);
        for (JsonNode booking : declined.body()) {
            assertThat(booking.path("status").asString()).isEqualTo("PENDING");
            assertThat(booking.path("failureReason").asString()).isEqualTo("Your card was declined.");
        }
        assertThat(capacity(eventId)).isEqualTo(3);
        int first = declined.body().get(0).path("id").asInt();
        int second = declined.body().get(1).path("id").asInt();

        // Retrying one ticket pays the whole order with one charge
        Response retry = pay(eventId, second, "pm_card_visa");
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(bookingRow(first).get("status")).isEqualTo("CONFIRMED");
        assertThat(bookingRow(first).get("payment_reference")).isEqualTo("42");

        // The other ticket is already paid: retrying it (as the frontend does, ticket by ticket) charges nothing
        Response again = pay(eventId, first, "pm_card_visa");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("status").asString()).isEqualTo("CONFIRMED");
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/bookings")));
        PAYMENT.verify(2, postRequestedFor(urlEqualTo("/payments")));  // the declined charge and the paid one
        assertThat(receiveJson(SIGNUP_QUEUE, 5000).path("amount").asInt()).isEqualTo(3000);
        assertThat(receiveJson(SIGNUP_QUEUE, 1000)).isNull();
    }

    @Test
    void moreTicketsThanPlacesLeftAreRefusedAndNoneIsReserved() {
        int eventId = createEvent(2, "15.00");

        Response signup = signUp(eventId, 42, "pm_card_visa", 3);

        assertThat(signup.status()).isEqualTo(409);
        assertThat(signup.body().path("detail").asString())
                .isEqualTo("Event " + eventId + " has only 2 place(s) left, 3 requested");
        assertThat(capacity(eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
        PAYMENT.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aQuantityOverTheLimitIsABadRequest() {
        int eventId = createEvent(20, "15.00");

        assertThat(signUp(eventId, 42, "pm_card_visa", 11).status()).isEqualTo(400);
        assertThat(signUp(eventId, 42, "pm_card_visa", 0).status()).isEqualTo(400);
        assertThat(capacity(eventId)).isEqualTo(20);
    }

    @Test
    void aDeclinedCardKeepsThePlaceAndARetryPaysTheSamePaymentBooking() {
        int eventId = createEvent(5, "15.00");

        Response declined = signUp(eventId, 42, "pm_card_chargeDeclined");
        assertThat(declined.status()).isEqualTo(201);
        assertThat(declined.body().path("status").asString()).isEqualTo("PENDING");
        assertThat(declined.body().path("failureReason").asString()).isEqualTo("Your card was declined.");
        assertThat(capacity(eventId)).isEqualTo(4);
        int bookingId = declined.body().path("id").asInt();

        Response retry = pay(eventId, bookingId, "pm_card_visa");

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().path("status").asString()).isEqualTo("CONFIRMED");
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/bookings")));
        PAYMENT.verify(2, postRequestedFor(urlEqualTo("/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.bookingId", equalTo("42"))));

        // A confirmed booking is not paid again: returned as it is, without asking the payment service
        Response again = pay(eventId, bookingId, "pm_card_visa");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("status").asString()).isEqualTo("CONFIRMED");
        PAYMENT.verify(2, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void aLostPaymentAnswerIsReconciledWithoutChargingTwice() {
        int eventId = createEvent(5, "15.00");
        // The card is charged, but the answer never arrives (503 from a proxy in between)
        PaymentStubs.chargedButAnswerLost(PAYMENT, 42, PaymentStubs.error(503, "Service Unavailable"));

        Response signup = signUp(eventId, 42, "pm_card_visa");
        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.body().path("status").asString()).isEqualTo("PENDING");
        assertThat(signup.body().path("failureReason").isNull()).isTrue();

        Response retry = pay(eventId, signup.body().path("id").asInt(), "pm_card_visa");

        // The retry's 409 sends the service looking for the payment, and it finds the PAID one: charged once
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(retry.body().path("paymentReference").asString()).isEqualTo("42");
    }

    @Test
    void aPaymentServiceOutageKeepsThePlaceAndTheRetryReports503UntilItIsBack() {
        int eventId = createEvent(5, "15.00");
        var outage = PAYMENT.stubFor(WireMock.any(anyUrl()).atPriority(OVERRIDE)
                .willReturn(PaymentStubs.error(503, "Service Unavailable")));

        Response signup = signUp(eventId, 42, "pm_card_visa");
        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.body().path("status").asString()).isEqualTo("PENDING");
        assertThat(signup.body().path("paymentBookingId").isNull()).isTrue();
        assertThat(capacity(eventId)).isEqualTo(4);
        int bookingId = signup.body().path("id").asInt();

        Response retryWhileDown = pay(eventId, bookingId, "pm_card_visa");
        assertThat(retryWhileDown.status()).isEqualTo(503);
        assertThat(retryWhileDown.body().path("detail").asString())
                .isEqualTo("The payment service is unavailable, please try again");

        PAYMENT.removeStub(outage);
        assertThat(pay(eventId, bookingId, "pm_card_visa").body().path("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void aPaidEventWithoutAPaymentMethodIsRejectedAndTakesNoPlace() {
        int eventId = createEvent(5, "15.00");

        Response signup = signUp(eventId, 42, null);

        assertThat(signup.status()).isEqualTo(400);
        assertThat(capacity(eventId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
        PAYMENT.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aFreeEventIsConfirmedWithoutThePaymentService() {
        int eventId = createEvent(5, null);

        Response signup = signUp(eventId, 42, null);

        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.body().path("status").asString()).isEqualTo("CONFIRMED");
        PAYMENT.verify(0, anyRequestedFor(anyUrl()));
        JsonNode notification = receiveJson(SIGNUP_QUEUE, 5000);
        assertThat(notification.path("user_id").asInt()).isEqualTo(42);
        assertThat(notification.has("amount")).isFalse();  // free: no "Paid" line in the email
    }

    @Test
    void concurrentSignupsNeverOversell() throws Exception {
        int capacity = 3;
        int users = 10;
        int eventId = createEvent(capacity, "15.00");
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try {
            for (int user = 1; user <= users; user++) {
                int userId = user;
                statuses.add(pool.submit(() -> {
                    start.await();
                    return signUp(eventId, userId, "pm_card_visa").status();
                }));
            }
            start.countDown();  // release every signup at once
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get());
            }

            assertThat(results).filteredOn(s -> s == 201).hasSize(capacity);
            assertThat(results).filteredOn(s -> s == 409).hasSize(users - capacity);
        } finally {
            pool.shutdownNow();
        }
        assertThat(capacity(eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE status = 'CONFIRMED'", Integer.class))
                .isEqualTo(capacity);
        PAYMENT.verify(capacity, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void aPaymentThatSucceedsAfterTheEventWasCancelledIsRefunded() {
        int eventId = createEvent(5, "15.00");
        // The card is charged slowly, so the organiser can cancel the event while the payment is in flight
        PAYMENT.stubFor(WireMock.post(urlEqualTo("/payments")).atPriority(OVERRIDE)
                .willReturn(PaymentStubs.paid(42).withFixedDelay(1000)));

        CompletableFuture<Response> signup = CompletableFuture.supplyAsync(() -> signUp(eventId, 42, "pm_card_visa"));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !PAYMENT.findAll(postRequestedFor(urlEqualTo("/payments"))).isEmpty());
        assertThat(post("/events/" + eventId + "/cancel").status()).isEqualTo(200);
        Response response = signup.join();

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("status").asString()).isEqualTo("CANCELLED");
        Map<String, Object> row = bookingRow(response.body().path("id").asInt());
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("payment_reference")).isEqualTo("42");
        assertThat(row.get("refund_required_at")).isNotNull();
        assertThat(row.get("refunded_at")).isNotNull();
        assertThat(row.get("refund_reference")).isEqualTo("REFUNDED");
        PAYMENT.verify(1, postRequestedFor(urlEqualTo("/payments/42/refund"))
                .withHeader("X-Service-Api-Key", equalTo(SERVICE_API_KEY)));
        assertThat(receiveJson(SIGNUP_QUEUE, 1000)).isNull();  // no "You're in!" email for a refunded booking
    }

    @Test
    void aPaymentFoundByRecoveryIsNotRefundedWhenItsOwnRequestFinishesLater() {
        int eventId = createEvent(5, "15.00");
        // The charge takes a while; meanwhile the payment service already lists the payment as PAID
        PAYMENT.stubFor(WireMock.post(urlEqualTo("/payments")).atPriority(OVERRIDE)
                .willReturn(PaymentStubs.paid(42).withFixedDelay(1000)));
        PAYMENT.stubFor(WireMock.get(urlEqualTo("/payments/booking/42")).atPriority(OVERRIDE)
                .willReturn(PaymentStubs.paymentsOfBooking(42, "PAID")));

        CompletableFuture<Response> signup = CompletableFuture.supplyAsync(() -> signUp(eventId, 42, "pm_card_visa"));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !PAYMENT.findAll(postRequestedFor(urlEqualTo("/payments"))).isEmpty());
        scheduler.recoverPayments();  // confirms the booking while its own request is still waiting
        Response response = signup.join();

        // The request's own PAID answer finds the booking already confirmed by that payment: no refund
        assertThat(response.body().path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(bookingRow(response.body().path("id").asInt()).get("refund_required_at")).isNull();
        PAYMENT.verify(0, postRequestedFor(WireMock.urlMatching("/payments/.*/refund")));
    }
}
