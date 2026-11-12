package events.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Integration tests of the auto-cancel rule against a real PostgreSQL and RabbitMQ: once an event's signup deadline
 * (expiresAt) passes with fewer than minSignUps signups, the scheduled check cancels it as an organiser would,
 * including the event.cancelled refund request. The tests run the check themselves; the cron schedule is disabled
 * in IntegrationTestBase.
 */
class EventExpiryIT extends IntegrationTestBase {

    // Stored in UTC, like every timestamp of this service
    private static final LocalDateTime IN_A_WEEK =
            LocalDateTime.now(ZoneOffset.UTC).plusDays(7).truncatedTo(ChronoUnit.SECONDS);

    @Test
    void anEventWithASignupDeadlineStartsWithMaxCapacityAtItsCapacity() {
        int eventId = createExpiringEvent(5, 2);

        signUp(eventId, 42, "pm_card_visa");

        JsonNode event = get("/events/" + eventId).body();
        assertThat(event.path("capacity").asInt()).isEqualTo(4);
        assertThat(event.path("maxCapacity").asInt()).isEqualTo(5);  // 5 - 4 = 1 signup so far
        assertThat(event.path("minSignUps").asInt()).isEqualTo(2);
        assertThat(LocalDateTime.parse(event.path("expiresAt").asString())).isEqualTo(IN_A_WEEK);
    }

    @Test
    void anExpiredEventThatMissedItsMinimumIsCancelledAndThePaymentServiceAskedToRefund() {
        int eventId = createExpiringEvent(5, 2);
        int bookingId = signUp(eventId, 42, "pm_card_visa").body().path("id").asInt();
        deadlinePasses(eventId);

        scheduler.checkExpiredEvents();

        assertThat(get("/events/" + eventId).status()).isEqualTo(404);
        assertThat(bookingRow(bookingId).get("status")).isEqualTo("CANCELLED");
        JsonNode cancelled = receiveJson(CANCELLED_QUEUE, 5000);
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.path("eventId").asInt()).isEqualTo(eventId);
    }

    @Test
    void anExpiredEventThatReachedItsMinimumAndAnEventStillOpenAreKept() {
        int reached = createExpiringEvent(5, 1);
        signUp(reached, 42, "pm_card_visa");
        deadlinePasses(reached);
        int stillOpen = createExpiringEvent(5, 2);  // no signups yet, but its deadline is a week away

        scheduler.checkExpiredEvents();

        assertThat(get("/events/" + reached).body().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(get("/events/" + stillOpen).status()).isEqualTo(200);
        assertThat(receiveJson(CANCELLED_QUEUE, 1000)).isNull();
    }

    // A paid event that needs minSignUps signups by IN_A_WEEK
    private int createExpiringEvent(int capacity, int minSignUps) {
        Response created = post("/events", """
                {"title": "Picnic", "capacity": %d, "ticketPrice": 15.00, "minSignUps": %d, "expiresAt": "%s"}
                """.formatted(capacity, minSignUps, IN_A_WEEK));
        assertThat(created.status()).isEqualTo(201);
        return created.body().path("id").asInt();
    }

    // Moves the event's signup deadline to a minute ago
    private void deadlinePasses(int eventId) {
        jdbc.update("UPDATE events SET expires_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), eventId);
    }
}
