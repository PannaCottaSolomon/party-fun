package payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static payment.integration.IntegrationClient.randomId;
import static payment.integration.IntegrationClient.stripeEvent;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import payment.integration.IntegrationClient.Response;

/**
 * Black-box tests of payment creation, lookups and webhooks, with WireMock standing in for Stripe. As with Stripe's
 * test PaymentMethods, the method chooses the outcome (ci/wiremock/mappings): {@code pm_card_visa} succeeds,
 * {@code pm_card_visa_chargeDeclined} is declined,
 * {@code pm_card_threeDSecure2Required} needs 3D Secure, and no method leaves the PaymentIntent pending.
 * Webhooks are signed by the test with the secret the container verifies them with.
 */
class PaymentApiIT {

    private static IntegrationClient api;

    @BeforeAll
    static void connect() {
        api = IntegrationClient.get();
    }

    @Test
    void successfulPaymentConfirmsBooking() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId(), 40.00);

        Map<String, Object> payment = api.pay(booking.get("bookingId"), "pm_card_visa");

        assertThat(payment).containsEntry("status", "PAID").containsEntry("amount", 40.0).containsEntry("currency", "SGD");
        assertThat((String) payment.get("stripePaymentIntentId")).startsWith("pi_");
        assertThat(payment.get("paidAt")).isNotNull();
        assertThat(api.bookingStatus(booking.get("bookingId"))).isEqualTo("CONFIRMED");
    }

    @Test
    void declinedPaymentIsFailed() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId());

        Map<String, Object> payment = api.pay(booking.get("bookingId"), "pm_card_visa_chargeDeclined");

        assertThat(payment.get("status")).isEqualTo("FAILED");
        assertThat(((String) payment.get("failureReason")).toLowerCase()).contains("declined");
        assertThat(api.bookingStatus(booking.get("bookingId"))).isEqualTo("PENDING");
    }

    @Test
    void paidBookingCannotBePaidAgain() {
        Map<String, Object> booking = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(0);

        Response again = api.post("/payments", Map.of("bookingId", booking.get("bookingId"),
                "paymentMethodId", "pm_card_visa"));

        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    void paymentForUnknownBookingIs404() {
        Response response = api.post("/payments", Map.of("bookingId", 2147483000, "paymentMethodId", "pm_card_visa"));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void pendingPaymentIsCompletedByWebhookIdempotently() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId());
        Map<String, Object> payment = api.pay(booking.get("bookingId"), null);
        assertThat(payment.get("status")).isEqualTo("PENDING");
        assertThat((String) payment.get("clientSecret")).isNotBlank();

        Map<String, Object> event = stripeEvent("payment_intent.succeeded", Map.of(
                "id", payment.get("stripePaymentIntentId"), "object", "payment_intent",
                "payment_method_types", List.of("card")));
        Response first = api.webhook(event);
        Response replay = api.webhook(event);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.field("action")).isEqualTo("PROCESSED");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.field("action")).isEqualTo("ALREADY_PROCESSED");
        Map<String, Object> current = api.get("/payments/" + payment.get("paymentId")).map();
        assertThat(current.get("status")).isEqualTo("PAID");
        assertThat(current.get("paidAt")).isNotNull();
        assertThat(api.bookingStatus(booking.get("bookingId"))).isEqualTo("CONFIRMED");
    }

    @Test
    void webhookForUnknownIntentIsAcknowledged() {
        Response response = api.webhook(stripeEvent("payment_intent.succeeded", Map.of("id", "pi_does_not_exist")));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.field("action")).isEqualTo("IGNORED");
    }

    @Test
    void invalidWebhookBodyIs400() {
        assertThat(api.postRaw("/payments/webhook/stripe", "not json").status()).isEqualTo(400);
    }

    @Test
    void unsignedForgedOrLiveModeWebhooksAreRejected() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId());
        Map<String, Object> payment = api.pay(booking.get("bookingId"), null);
        Map<String, Object> object = Map.of("id", payment.get("stripePaymentIntentId"), "object", "payment_intent");

        // No Stripe-Signature: someone trying to mark a payment as paid.
        Response unsigned = api.post("/payments/webhook/stripe", stripeEvent("payment_intent.succeeded", object));
        Response live = api.webhook(Map.of("id", "evt_it_live", "type", "payment_intent.succeeded", "livemode", true,
                "data", Map.of("object", object)));

        assertThat(unsigned.status()).isEqualTo(400);
        assertThat(live.status()).isEqualTo(400);
        assertThat(api.paymentStatus(payment.get("paymentId"))).isEqualTo("PENDING");
    }

    @Test
    void cardNeedingThreeDSecureStaysPendingNeverPaid() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId());

        Map<String, Object> payment = api.pay(booking.get("bookingId"), "pm_card_threeDSecure2Required");

        assertThat(payment.get("status")).isEqualTo("PENDING");
        assertThat((String) payment.get("clientSecret")).isNotBlank();
        assertThat(api.bookingStatus(booking.get("bookingId"))).isEqualTo("PENDING");
    }

    @Test
    void paymentLookups() {
        int userId = randomId();
        List<Map<String, Object>> first = api.paidBooking(userId, randomId(), "pm_card_visa");
        List<Map<String, Object>> second = api.paidBooking(userId, randomId(), "pm_card_visa");
        Object bookingId = first.get(0).get("bookingId");
        Object paymentId = first.get(1).get("paymentId");

        Response byId = api.get("/payments/" + paymentId);
        assertThat(byId.status()).isEqualTo(200);
        assertThat(byId.field("bookingId")).isEqualTo(bookingId);

        Response byBooking = api.get("/payments/booking/" + bookingId);
        assertThat(byBooking.status()).isEqualTo(200);
        assertThat(byBooking.list()).extracting(p -> p.get("paymentId")).containsExactly(paymentId);

        Response byUser = api.get("/payments/user/" + userId);
        assertThat(byUser.status()).isEqualTo(200);
        assertThat(byUser.list()).extracting(p -> p.get("paymentId"))
                .containsExactlyInAnyOrder(paymentId, second.get(1).get("paymentId"));
        assertThat(byUser.list()).allSatisfy(p -> assertThat(p.get("userId")).isEqualTo(userId));

        Response all = api.get("/payments");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.list()).extracting(p -> p.get("paymentId")).contains(paymentId, second.get(1).get("paymentId"));

        assertThat(api.get("/payments/2147483000").status()).isEqualTo(404);
        assertThat(api.get("/payments/booking/2147483000").status()).isEqualTo(404);
        assertThat(api.get("/payments/not-a-number").status()).isEqualTo(400);
    }

    @Test
    void noGenericPaymentMutationEndpoints() {
        Object paymentId = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(1).get("paymentId");
        String path = "/payments/" + paymentId;

        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            assertThat(api.request(method, path, Map.of("status", "REFUNDED")).status()).as(method).isEqualTo(405);
        }
        assertThat(api.paymentStatus(paymentId)).isEqualTo("PAID");
    }

    @Test
    void checkoutChargesTicketPriceTimesQuantityAndIsIdempotent() {
        String eventBookingId = "evb-" + randomId();
        int userId = randomId();
        int eventId = randomId();

        Response first = api.checkout(eventBookingId, userId, eventId, "pm_card_visa");
        Response again = api.checkout(eventBookingId, userId, eventId, "pm_card_visa");

        assertThat(first.status()).as(first.text()).isEqualTo(201);
        assertThat(first.field("status")).isEqualTo("PAID");
        assertThat(first.field("amount")).isEqualTo(40.0);
        assertThat(first.field("eventBookingId")).isEqualTo(eventBookingId);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.field("paymentId")).isEqualTo(first.field("paymentId"));
        assertThat(api.get("/payments/booking/" + first.field("bookingId")).list()).hasSize(1);
        assertThat(api.bookingStatus(first.field("bookingId"))).isEqualTo("CONFIRMED");
    }

    @Test
    void checkoutDeclinedThenRetriedOnTheSameBooking() {
        String eventBookingId = "evb-" + randomId();
        int userId = randomId();
        int eventId = randomId();

        Response declined = api.checkout(eventBookingId, userId, eventId, "pm_card_visa_chargeDeclined");
        Response retry = api.checkout(eventBookingId, userId, eventId, "pm_card_visa");

        assertThat(declined.field("status")).isEqualTo("FAILED");
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.field("status")).isEqualTo("PAID");
        assertThat(retry.field("bookingId")).isEqualTo(declined.field("bookingId"));
    }

    @Test
    void checkoutWithoutPaymentMethodStaysPending() {
        Response pending = api.checkout("evb-" + randomId(), randomId(), randomId(), null);

        assertThat(pending.status()).isEqualTo(201);
        assertThat(pending.field("status")).isEqualTo("PENDING");
        assertThat((String) pending.field("clientSecret")).isNotBlank();
        assertThat(pending.field("paidAt")).isNull();
    }

    @Test
    void syncRecordsAPaymentConfirmedAtStripeWithoutAWebhook() {
        Response pending = api.checkout("evb-" + randomId(), randomId(), randomId(), null);
        Object paymentId = pending.field("paymentId");
        Response untouched = api.post("/payments/" + paymentId + "/sync", null);
        assertThat(untouched.status()).isEqualTo(200);
        assertThat(untouched.field("status")).isEqualTo("PENDING");

        api.confirmAtStripe((String) pending.field("stripePaymentIntentId"), "pm_card_visa");
        Response synced = api.post("/payments/" + paymentId + "/sync", null);

        assertThat(synced.status()).as(synced.text()).isEqualTo(200);
        assertThat(synced.field("status")).isEqualTo("PAID");
        assertThat(synced.field("paidAt")).isNotNull();
        assertThat(api.bookingStatus(synced.field("bookingId"))).isEqualTo("CONFIRMED");
        assertThat(api.post("/payments/" + paymentId + "/sync", null).field("status")).isEqualTo("PAID");
        assertThat(api.post("/payments/999999/sync", null).status()).isEqualTo(404);
    }

    @Test
    void checkoutConflictsAndValidation() {
        String eventBookingId = "evb-" + randomId();
        int userId = randomId();
        assertThat(api.checkout(eventBookingId, userId, randomId(), "pm_card_visa").status()).isEqualTo(201);

        // Same Event Service booking id, different event: rejected rather than charged again.
        assertThat(api.checkout(eventBookingId, userId, randomId(), "pm_card_visa").status()).isEqualTo(409);
        Response invalid = api.post("/payments/checkout", Map.of("eventId", 1, "quantity", 0, "ticketPrice", -1));
        assertThat(invalid.status()).isEqualTo(400);
    }
}
