package events.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;

import tools.jackson.databind.JsonNode;

/**
 * Chaos experiments on the broker side (JUnit tag {@code chaos}: {@code mvnw verify -Pchaos}, CI job chaos-tests).
 *
 * <p>The refunds of a cancelled event hang on one message, event.cancelled, which the payment service consumes.
 * These experiments take RabbitMQ away (Toxiproxy cuts the service's only connection to it) or leave the message
 * nowhere to go, at the moment an event is cancelled, and check that the message waits in the outbox instead of
 * being dropped, and is delivered once the broker can take it.
 */
@Tag("chaos")
class BrokerChaosIT extends IntegrationTestBase {

    @Test
    void anEventCancelledWhileTheBrokerIsDownIsDeliveredOnceItIsBack() throws IOException {
        int eventId = createEvent(5, "15.00");
        int bookingId = signUp(eventId, 42, "pm_card_visa").body().path("id").asInt();

        BROKER.disable();
        Response cancel = post("/events/" + eventId + "/cancel");

        // The cancellation succeeds; the refund request waits in the outbox instead of being dropped
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(bookingRow(bookingId).get("status")).isEqualTo("CANCELLED");
        List<Map<String, Object>> waiting = outboxRows();
        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).get("routing_key")).isEqualTo("event.cancelled");
        assertThat(waiting.get(0).get("attempts")).isEqualTo(1);

        scheduler.relayOutbox();  // still down: kept again
        assertThat(outboxRows()).singleElement().satisfies(row -> assertThat(row.get("attempts")).isEqualTo(2));

        BROKER.enable();
        scheduler.relayOutbox();

        assertThat(outboxRows()).isEmpty();
        JsonNode cancelled = receiveJson(CANCELLED_QUEUE, 5000);
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.path("eventId").asInt()).isEqualTo(eventId);
        assertThat(cancelled.path("requestId").asString()).isEqualTo("cancel-" + eventId);
    }

    @Test
    void anAutoCancelDuringABrokerOutageStillAsksForTheRefunds() throws IOException {
        // Nobody is watching this one: the scheduled check cancels an event that missed its minimum signups
        LocalDateTime inAWeek = LocalDateTime.now(ZoneOffset.UTC).plusDays(7).truncatedTo(ChronoUnit.SECONDS);
        int eventId = post("/events", """
                {"title": "Picnic", "capacity": 5, "ticketPrice": 15.00, "minSignUps": 2, "expiresAt": "%s"}
                """.formatted(inAWeek)).body().path("id").asInt();
        signUp(eventId, 42, "pm_card_visa");
        jdbc.update("UPDATE events SET expires_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), eventId);

        BROKER.disable();
        scheduler.checkExpiredEvents();

        assertThat(get("/events/" + eventId).status()).isEqualTo(404);
        assertThat(outboxRows()).hasSize(1);

        BROKER.enable();
        scheduler.relayOutbox();

        assertThat(outboxRows()).isEmpty();
        assertThat(receiveJson(CANCELLED_QUEUE, 5000).path("eventId").asInt()).isEqualTo(eventId);
    }

    @Test
    void anEventCancelledThatNoQueueIsBoundForWaitsInTheOutboxUntilOneIs() {
        int eventId = createEvent(5, "15.00");
        // Nothing consumes event.cancelled yet (e.g. the payment service has never started and declared its
        // queue): a plain publish would be dropped by the broker without an error
        amqpAdmin.removeBinding(new Binding(CANCELLED_QUEUE, Binding.DestinationType.QUEUE, "party.events",
                "event.cancelled", null));

        assertThat(post("/events/" + eventId + "/cancel").status()).isEqualTo(200);

        assertThat(outboxRows()).singleElement()
                .satisfies(row -> assertThat((String) row.get("last_error")).startsWith("unroutable"));

        captureQueue(CANCELLED_QUEUE, "party.events", "event.cancelled");  // the consumer's queue appears
        scheduler.relayOutbox();

        assertThat(outboxRows()).isEmpty();
        assertThat(receiveJson(CANCELLED_QUEUE, 5000).path("eventId").asInt()).isEqualTo(eventId);
    }
}
