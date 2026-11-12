package events.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Integration tests of the RabbitMQ side of the saga against a real broker: the event.cancelled message the payment
 * service consumes to cancel its bookings and refund the paid ones. It is a one-way request: the payment service
 * handles the refunds on its side and sends no confirmation back to this service. It goes through the outbox, which
 * is empty again once the broker has confirmed it (what happens while the broker is down: BrokerChaosIT).
 */
class PaymentMessagingIT extends IntegrationTestBase {

    @Test
    void cancellingAnEventCancelsItsBookingsAndAsksThePaymentServiceForRefunds() {
        int eventId = createEvent(5, "15.00");
        int bookingId = signUp(eventId, 42, "pm_card_visa").body().path("id").asInt();

        assertThat(post("/events/" + eventId + "/cancel").status()).isEqualTo(200);

        assertThat(outboxRows()).isEmpty();  // confirmed by the broker before the request returned
        JsonNode cancelled = receiveJson(CANCELLED_QUEUE, 5000);
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.path("eventId").isNumber()).isTrue();  // the payment service expects a JSON number
        assertThat(cancelled.path("eventId").asInt()).isEqualTo(eventId);
        assertThat(cancelled.path("requestId").asString()).isEqualTo("cancel-" + eventId);
        assertThat(cancelled.path("timestamp").asString()).endsWith("Z");
        assertThat(bookingRow(bookingId).get("status")).isEqualTo("CANCELLED");
        assertThat(get("/events/" + eventId).status()).isEqualTo(404);
    }

    @Test
    void deletingAnEventCancelsItsBookingsWithoutAskingForRefunds() {
        int eventId = createEvent(5, "15.00");
        int bookingId = signUp(eventId, 42, "pm_card_visa").body().path("id").asInt();

        assertThat(delete("/events/" + eventId).status()).isEqualTo(204);

        assertThat(bookingRow(bookingId).get("status")).isEqualTo("CANCELLED");
        assertThat(receiveJson(CANCELLED_QUEUE, 1000)).isNull();
        assertThat(outboxRows()).isEmpty();
    }
}
