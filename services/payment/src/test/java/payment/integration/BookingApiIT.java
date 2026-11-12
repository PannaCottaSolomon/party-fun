package payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static payment.integration.IntegrationClient.randomId;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import payment.integration.IntegrationClient.Response;

/** Black-box tests of the health and booking endpoints against the running container. */
class BookingApiIT {

    private static IntegrationClient api;

    @BeforeAll
    static void connect() {
        api = IntegrationClient.get();
    }

    @Test
    void health() {
        Response response = api.get("/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.map()).isEqualTo(Map.of("service", "payment", "status", "UP"));
    }

    @Test
    void actuatorExposesOnlyHealthWithoutDetails() {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            Response response = api.get(path);
            assertThat(response.status()).as(path).isEqualTo(200);
            assertThat(response.map()).as(path).containsEntry("status", "UP")
                    // No database, broker or version details leak (only the probe group names on /actuator/health).
                    .doesNotContainKeys("components", "details");
        }
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/info", "/actuator/heapdump")) {
            assertThat(api.get(path).status()).as(path).isEqualTo(404);
        }
    }

    @Test
    void createGetAndListBookings() {
        int userId = randomId();
        int eventId = randomId();
        Map<String, Object> booking = api.createBooking(userId, eventId, 40.00);

        assertThat(booking).containsEntry("status", "PENDING").containsEntry("userId", userId)
                .containsEntry("eventId", eventId).containsEntry("totalAmount", 40.0).containsEntry("currency", "SGD");

        Response fetched = api.get("/bookings/" + booking.get("bookingId"));
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.field("bookingId")).isEqualTo(booking.get("bookingId"));

        Map<String, Object> second = api.createBooking(userId, randomId(), 10.00);
        Response list = api.get("/bookings/user/" + userId);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.list()).extracting(b -> b.get("bookingId"))
                .containsExactlyInAnyOrder(booking.get("bookingId"), second.get("bookingId"));

        Response all = api.get("/bookings");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.list()).extracting(b -> b.get("bookingId"))
                .contains(booking.get("bookingId"), second.get("bookingId"));
    }

    @Test
    void validationErrors() {
        Response zeroQuantity = api.post("/bookings",
                Map.of("userId", 1, "eventId", 1, "quantity", 0, "totalAmount", 40.0));
        assertThat(zeroQuantity.status()).isEqualTo(400);
        assertThat(zeroQuantity.mapField("fieldErrors")).containsKey("quantity");

        Response empty = api.post("/bookings", Map.of());
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.mapField("fieldErrors")).containsKeys("userId", "eventId", "quantity", "totalAmount");

        Response malformed = api.postRaw("/bookings", "{not json");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.text().toLowerCase()).doesNotContain("trace").doesNotContain("exception");
    }

    @Test
    void unknownBookingIs404() {
        Response response = api.get("/bookings/2147483000");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.map()).containsEntry("status", 404).containsEntry("error", "Not Found")
                .containsEntry("path", "/bookings/2147483000");
    }
}
