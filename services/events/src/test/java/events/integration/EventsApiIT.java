package events.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Integration tests of the event REST API against a real PostgreSQL: the full HTTP → JPA → database path, schema
 * generation, and RFC 9457 error bodies.
 */
class EventsApiIT extends IntegrationTestBase {

    @Test
    void anEventCanBeCreatedReadListedUpdatedAndDeleted() {
        Response created = post("/events", """
                {"title": "CS302 Party", "location": "SCIS 1", "startDateTime": "2026-11-20T19:00:00",
                 "endDateTime": "2026-11-20T23:00:00", "capacity": 100, "ticketPrice": 15.00, "currency": "sgd"}
                """);
        assertThat(created.status()).isEqualTo(201);
        int id = created.body().path("id").asInt();
        assertThat(created.body().path("currency").asString()).isEqualTo("SGD");
        assertThat(created.body().path("status").asString()).isEqualTo("ACTIVE");

        Response read = get("/events/" + id);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().path("title").asString()).isEqualTo("CS302 Party");
        assertThat(read.body().path("startDateTime").asString()).isEqualTo("2026-11-20T19:00:00");

        assertThat(get("/events").body()).hasSize(1);

        Response updated = patch("/events/" + id, "{\"location\": \"SCIS 2\"}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("location").asString()).isEqualTo("SCIS 2");
        assertThat(updated.body().path("title").asString()).isEqualTo("CS302 Party");

        assertThat(delete("/events/" + id).status()).isEqualTo(204);
        Response gone = get("/events/" + id);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().path("detail").asString()).isEqualTo("Event " + id + " not found");
    }

    // Called without a token, as Docker's HEALTHCHECK and orchestrator probes do
    @Test
    void healthProbesReportUpWhenTheDatabaseAndBrokerAreReachable() {
        for (String probe : new String[] { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" }) {
            Response health = call(HttpMethod.GET, probe, null, null);
            assertThat(health.status()).as(probe).isEqualTo(200);
            assertThat(health.body().path("status").asString()).as(probe).isEqualTo("UP");
        }
    }

    @Test
    void invalidEventsAreRejectedWithAProblemDetail() {
        Response usd = post("/events", "{\"title\": \"X\", \"capacity\": 5, \"ticketPrice\": 10, \"currency\": \"USD\"}");
        assertThat(usd.status()).isEqualTo(400);
        assertThat(usd.body().path("detail").asString()).contains("SGD");

        Response noTitle = post("/events", "{\"capacity\": 5}");
        assertThat(noTitle.status()).isEqualTo(400);
        assertThat(noTitle.body().path("detail").asString()).isEqualTo("Event title is required");

        assertThat(get("/events").body()).isEmpty();
    }
}
