package payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static payment.integration.IntegrationClient.OBSERVER_QUEUE;
import static payment.integration.IntegrationClient.forPayment;
import static payment.integration.IntegrationClient.randomId;
import static payment.integration.IntegrationClient.routedAs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import payment.integration.IntegrationClient.Message;

/**
 * Black-box tests of the choreography Saga step started by the Event Service's {@code event.cancelled}
 * ({@code party.events} → {@code payments.refunds.requested}): Payment refunds every paid payment of the event
 * individually itself (refunds at Stripe, here WireMock), and publishes each failed one to the refund DLQ.
 */
class RefundSagaIT {

    private static IntegrationClient api;

    @BeforeAll
    static void connect() {
        api = IntegrationClient.get();
    }

    @Test
    void eventCancellationRefundsPartiallyAndIsIdempotent() {
        int eventId = randomId();
        List<Map<String, Object>> good = api.paidBooking(randomId(), eventId, "pm_card_visa");
        List<Map<String, Object>> alsoGood = api.paidBooking(randomId(), eventId, "pm_card_visa");
        List<Map<String, Object>> bad = api.paidBooking(randomId(), eventId, "pm_card_visa");
        // Refunded behind the service's back: Stripe refuses the service's refund (charge_already_refunded).
        api.refundAtStripe((String) bad.get(1).get("stripePaymentIntentId"));
        Object unpaidBookingId = api.createBooking(randomId(), eventId).get("bookingId");
        List<Map<String, Object>> otherEvent = api.paidBooking(randomId(), randomId(), "pm_card_visa");
        Object goodPaymentId = good.get(1).get("paymentId");
        Object badPaymentId = bad.get(1).get("paymentId");

        Map<String, Object> request = Map.of("eventId", eventId, "requestId", "cancel-" + eventId,
                "timestamp", "2026-10-06T10:00:00Z");
        api.publish("party.events", "event.cancelled", request);

        Object alsoGoodPaymentId = alsoGood.get(1).get("paymentId");
        assertThat(IntegrationClient.waitUntil(() -> "REFUNDED".equals(api.paymentStatus(goodPaymentId))
                && "REFUNDED".equals(api.paymentStatus(alsoGoodPaymentId))
                && "REFUND_FAILED".equals(api.paymentStatus(badPaymentId)), Duration.ofSeconds(30))).isTrue();

        // Partial refund: the successful one stays successful; only the failing one was dead-lettered.
        assertThat(api.bookingStatus(good.get(0).get("bookingId"))).isEqualTo("REFUNDED");
        // Retry Saga, no rollback: the failed participant's booking stays CANCELLED.
        assertThat(api.bookingStatus(bad.get(0).get("bookingId"))).isEqualTo("CANCELLED");
        assertThat(api.bookingStatus(unpaidBookingId)).isEqualTo("CANCELLED");
        // Other events are untouched.
        assertThat(api.paymentStatus(otherEvent.get(1).get("paymentId"))).isEqualTo("PAID");
        assertThat(api.bookingStatus(otherEvent.get(0).get("bookingId"))).isEqualTo("CONFIRMED");

        // The successful refund emails its participant directly (notify.refund); the failed one alerts the admin.
        Predicate<Message> refunded = routedAs("notify.refund").and(forPayment(goodPaymentId));
        List<Message> emails = api.waitFor(OBSERVER_QUEUE, refunded);
        assertThat(emails.get(0).map()).containsEntry("event_title", "IT event " + eventId)
                .containsEntry("amount", 4000).containsEntry("registration_id", good.get(1).get("eventBookingId"));
        assertThat(api.waitFor(OBSERVER_QUEUE, routedAs("notify.admin_alert").and(forPayment(badPaymentId))))
                .hasSize(1);
        assertThat(api.messages(OBSERVER_QUEUE, routedAs("notify.refund").and(forPayment(badPaymentId)))).isEmpty();
        // Exactly one individual DLQ message, for the failed payment only.
        List<Message> dlq = api.waitForDlq(forPayment(badPaymentId));
        assertThat(dlq).hasSize(1);
        assertThat(dlq.get(0).map()).containsEntry("eventId", eventId).containsEntry("trigger", "EVENT_CANCELLED")
                .containsEntry("refundAmountCents", 4000).containsEntry("retryCount", 1)
                .containsEntry("errorCode", "charge_already_refunded");
        // Application-managed routing: published by Payment to the DLX, not dead-lettered by RabbitMQ (no x-death).
        assertThat(dlq.get(0).exchange()).isEqualTo("payments.refund.dlx");
        assertThat(dlq.get(0).death()).isEmpty();
        assertThat(api.dlq(forPayment(goodPaymentId))).isEmpty();
        assertThat(api.dlq(forPayment(alsoGoodPaymentId))).isEmpty();
        // The failure is also recorded in MySQL, visible on the Admin dashboard.
        assertThat(api.adminGet("/admin/refunds/failed").text()).contains("\"paymentId\":" + badPaymentId);

        // The same message again (e.g. a redelivery) must not refund twice or dead-letter twice.
        api.publish("party.events", "event.cancelled", request);
        IntegrationClient.sleep(5000);
        assertThat(api.dlq(forPayment(badPaymentId))).hasSize(1);
        assertThat(api.messages(OBSERVER_QUEUE, refunded)).hasSize(1);
        assertThat(api.paymentStatus(goodPaymentId)).isEqualTo("REFUNDED");
        assertThat(api.paymentStatus(badPaymentId)).isEqualTo("REFUND_FAILED");
    }

    @Test
    void malformedRefundRequestIsNotDeadLettered() {
        api.publish("party.events", "event.cancelled", "this is not json");
        api.publish("party.events", "event.cancelled", Map.of("requestId", "no-event-id"));
        IntegrationClient.sleep(3000);

        // The refund DLQ only ever holds failed refunds, each tied to a payment.
        assertThat(api.dlq(m -> true)).allSatisfy(m -> assertThat(m.map()).containsKey("paymentId"));
        assertThat(api.get("/health").status()).isEqualTo(200);
    }

    @Test
    void onlyEventCancelledStartsTheSaga() {
        // The team's contract uses event.cancelled only; the old names are no longer bound to Payment's queue.
        assertThat(api.publishAndReportRouted("party.events", "refunds.requested", Map.of("eventId", randomId())))
                .isFalse();
        assertThat(api.publishAndReportRouted("party.events", "events.cancelled", Map.of("eventId", randomId())))
                .isFalse();
    }

    @Test
    void acceptsTheEventServicesCurrentCancellationMessage() {
        int eventId = randomId();
        Object paymentId = api.paidBooking(randomId(), eventId, "pm_card_visa").get(1).get("paymentId");

        // Exactly what services/events publishes: EventCancelledMessage with a __TypeId__ header.
        String body = "{\"eventId\":" + eventId + ",\"requestId\":\"cancel-" + eventId + "\","
                + "\"timestamp\":\"2026-10-18T14:25:30.123Z\",\"reason\":\"Event cancelled by the organiser\"}";
        assertThat(api.publishAndReportRouted("party.events", "event.cancelled", body,
                Map.of("__TypeId__", "events.dto.EventCancelledMessage"))).isTrue();

        assertThat(IntegrationClient.waitUntil(() -> "REFUNDED".equals(api.paymentStatus(paymentId)),
                Duration.ofSeconds(30))).isTrue();
        assertThat(api.waitFor(OBSERVER_QUEUE, routedAs("notify.refund").and(forPayment(paymentId)))).hasSize(1);
    }
}
