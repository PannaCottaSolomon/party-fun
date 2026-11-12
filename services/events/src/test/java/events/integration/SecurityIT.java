package events.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Integration tests of authentication and authorisation over real HTTP: access tokens are verified like Cognito's
 * (signature and expiry), and their cognito:groups claim decides which endpoints the user may call. The full
 * endpoint-by-group matrix is in EventControllerTest; these check the running application end to end.
 */
class SecurityIT extends IntegrationTestBase {

    @Test
    void aRequestWithoutATokenIsRejectedAndChangesNothing() {
        assertThat(call(HttpMethod.GET, "/events", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/events", "{\"title\":\"X\",\"capacity\":5}", null).status()).isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Integer.class)).isZero();
    }

    @Test
    void aTokenNotSignedByTheUserPoolIsRejected() {
        String forged = token(rsaKeyPair(), Instant.now().plus(Duration.ofHours(1)), "admin");

        assertThat(call(HttpMethod.GET, "/events", null, forged).status()).isEqualTo(401);
    }

    @Test
    void anExpiredTokenIsRejected() {
        String expired = token(SIGNING_KEY, Instant.now().minus(Duration.ofHours(1)), "admin");

        assertThat(call(HttpMethod.GET, "/events", null, expired).status()).isEqualTo(401);
    }

    @Test
    void aParticipantCanBrowseAndSignUpButCannotManageEvents() {
        int eventId = createEvent(5, "15.00");
        String event = "/events/" + eventId;

        assertThat(call(HttpMethod.GET, event, null, PARTICIPANT).status()).isEqualTo(200);
        assertThat(signUp(eventId, 42, "pm_card_visa").status()).isEqualTo(201);

        assertThat(call(HttpMethod.POST, "/events", "{\"title\":\"Mine\",\"capacity\":5}", PARTICIPANT).status())
                .isEqualTo(403);
        assertThat(call(HttpMethod.PATCH, event, "{\"title\":\"Renamed\"}", PARTICIPANT).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, event + "/cancel", null, PARTICIPANT).status()).isEqualTo(403);
        assertThat(call(HttpMethod.DELETE, event, null, PARTICIPANT).status()).isEqualTo(403);

        assertThat(get("/events").body()).hasSize(1);
        assertThat(get(event).body().path("title").asString()).isEqualTo("IT event");
        assertThat(receiveJson(CANCELLED_QUEUE, 1000)).isNull();
    }

    @Test
    void anAdminCanManageEvents() {
        String admin = token("admin");

        Response created = call(HttpMethod.POST, "/events", "{\"title\":\"Admin event\",\"capacity\":5}", admin);
        assertThat(created.status()).isEqualTo(201);
        int eventId = created.body().path("id").asInt();
        assertThat(call(HttpMethod.POST, "/events/" + eventId + "/cancel", null, admin).status()).isEqualTo(200);
    }

    @Test
    void aUserInNoKnownGroupIsForbidden() {
        assertThat(call(HttpMethod.GET, "/events", null, token()).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/events", null, token("guest")).status()).isEqualTo(403);
    }
}
