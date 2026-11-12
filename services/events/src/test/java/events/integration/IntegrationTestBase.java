package events.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import events.controller.EventScheduler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class of the integration and chaos tests ({@code *IT}, run by Failsafe in {@code mvnw verify}).
 *
 * <p>The whole application runs on a random port against a real PostgreSQL and a real RabbitMQ in throw-away
 * Testcontainers (started once per test run and shared by every IT class), and against WireMock for the payment
 * service, with the default stubs in {@code src/test/resources/wiremock/payment} (see {@link PaymentStubs} for the
 * test-specific ones). The service reaches RabbitMQ through Toxiproxy, so a chaos test can take the broker away
 * ({@link #BROKER}) and stop the payment service ({@link #PAYMENT}) mid-test. Every connection property is set here,
 * so no local {@code .env} or running service is needed: only a Docker daemon. Each test starts with empty tables
 * and outbox, the default payment stubs, a reachable broker and empty capture queues.
 *
 * <p>Requests carry RS256-signed access tokens shaped like Cognito's ({@code cognito:groups} claim), signed with a
 * key generated for the test run; {@link TestJwtConfig} verifies them in place of the Cognito user pool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestBase.TestJwtConfig.class)
abstract class IntegrationTestBase {

    // Same major versions as local development (README) and as the services the app talks to in production
    static final Network NETWORK = Network.newNetwork();
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management")
            .withNetwork(NETWORK)
            .withNetworkAliases("rabbitmq");
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(NETWORK);
    static final Proxy BROKER;  // the service's (and the tests') only way to RabbitMQ: disable() cuts it

    // The payment service, on a fixed port so a test can stop() and start() it again (connection refused meanwhile)
    static final WireMockServer PAYMENT = new WireMockServer(WireMockConfiguration.options()
            .port(freePort())
            .usingFilesUnderClasspath("wiremock/payment"));
    // The internal key the service sends on refunds; the default refund stub refuses requests without one
    static final String SERVICE_API_KEY = "it-service-key";

    static {
        POSTGRES.start();
        RABBIT.start();
        TOXIPROXY.start();
        PAYMENT.start();
        try {
            BROKER = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("rabbitmq", "0.0.0.0:8666", "rabbitmq:5672");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // Stands in for the Cognito user pool's signing key
    static final KeyPair SIGNING_KEY = rsaKeyPair();

    // Access tokens of a user in each group the helpers act as (valid for an hour, far longer than a test run)
    static final String ORGANISER = token("organiser");
    static final String PARTICIPANT = token("participant");

    // Queues the tests bind to the service's exchanges to observe what it publishes
    static final String CANCELLED_QUEUE = "it.event-cancelled";
    static final String SIGNUP_QUEUE = "it.notify-signup";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", TOXIPROXY::getHost);
        registry.add("spring.rabbitmq.port", () -> TOXIPROXY.getMappedPort(8666));
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("events.payment.base-url", PAYMENT::baseUrl);
        registry.add("events.payment.service-api-key", () -> SERVICE_API_KEY);
        // Short enough for chaos tests to make the payment service hang past it (5 s) without slowing the run down,
        // long enough that a busy CI runner never times out a normal or briefly delayed (1 s) answer
        registry.add("events.payment.read-timeout", () -> "3s");
        registry.add("events.outbox.confirm-timeout", () -> "2s");
        // Payment recovery may check a booking as soon as its request has finished
        registry.add("events.payment.reconcile-after", () -> "0s");
        registry.add("spring.jpa.show-sql", () -> "false");
        // Tests run the scheduled jobs themselves (scheduler) rather than racing their schedules
        registry.add("app.scheduling.event-check-cron", () -> Scheduled.CRON_DISABLED);
        registry.add("events.outbox.relay-cron", () -> Scheduled.CRON_DISABLED);
        registry.add("events.payment.recovery-cron", () -> Scheduled.CRON_DISABLED);
    }

    // Replaces the Cognito decoder (built from spring.security.oauth2.resourceserver.jwt.issuer-uri) with one that
    // checks the signature and expiry of the tests' own tokens, so no AWS user pool or network access is needed
    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected AmqpAdmin amqpAdmin;
    @Autowired
    protected EventScheduler scheduler;  // runs the scheduled jobs on demand (their schedules are disabled)

    protected final JsonMapper json = JsonMapper.builder().build();
    protected RestClient http;

    @BeforeEach
    void resetState() throws IOException {
        // Undo whatever chaos the previous test left behind, even if it failed half-way
        BROKER.enable();
        if (!PAYMENT.isRunning()) {
            PAYMENT.start();
        }
        PAYMENT.resetToDefaultMappings();
        PAYMENT.resetRequests();
        PAYMENT.resetScenarios();

        jdbc.execute("TRUNCATE bookings, events, outbox_messages RESTART IDENTITY");
        captureQueue(CANCELLED_QUEUE, "party.events", "event.cancelled");
        captureQueue(SIGNUP_QUEUE, "notifications", "notify.signup");
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // ---------- HTTP helpers (never throw on 4xx/5xx: tests assert the status) ----------
    // They act as an organiser (allowed on every endpoint), except signUp and pay, which act as a participant.

    record Response(int status, JsonNode body) {
    }

    protected Response get(String path) {
        return call(HttpMethod.GET, path, null, ORGANISER);
    }

    protected Response post(String path) {
        return call(HttpMethod.POST, path, null, ORGANISER);
    }

    protected Response post(String path, String jsonBody) {
        return call(HttpMethod.POST, path, jsonBody, ORGANISER);
    }

    protected Response patch(String path, String jsonBody) {
        return call(HttpMethod.PATCH, path, jsonBody, ORGANISER);
    }

    protected Response delete(String path) {
        return call(HttpMethod.DELETE, path, null, ORGANISER);
    }

    // token is sent as "Authorization: Bearer <token>"; null sends no Authorization header at all
    protected Response call(HttpMethod method, String path, String jsonBody, String token) {
        RestClient.RequestBodySpec request = http.method(method).uri(path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), readBody(res.getBody())));
    }

    private JsonNode readBody(InputStream body) throws IOException {
        byte[] bytes = body.readAllBytes();
        return bytes.length == 0 ? null : json.readTree(bytes);
    }

    // ---------- access tokens ----------

    // A token like Cognito issues, for a user in the given groups (none: no cognito:groups claim, as Cognito does)
    protected static String token(String... groups) {
        return token(SIGNING_KEY, Instant.now().plus(Duration.ofHours(1)), groups);
    }

    protected static String token(KeyPair signingKey, Instant expiresAt, String... groups) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject("it-user")
                .issuedAt(expiresAt.minus(Duration.ofHours(1)))
                .expiresAt(expiresAt);
        if (groups.length > 0) {
            claims.claim("cognito:groups", List.of(groups));
        }
        NimbusJwtEncoder encoder = NimbusJwtEncoder
                .withKeyPair((RSAPublicKey) signingKey.getPublic(), (RSAPrivateKey) signingKey.getPrivate())
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    protected static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // ---------- domain helpers ----------

    protected int createEvent(int capacity, String ticketPrice) {
        String price = ticketPrice == null ? "" : ",\"ticketPrice\":" + ticketPrice;
        Response created = post("/events", "{\"title\":\"IT event\",\"capacity\":" + capacity + price + "}");
        if (created.status() != 201) {
            throw new IllegalStateException("Could not create event: " + created);
        }
        return created.body().path("id").asInt();
    }

    // Single-ticket signup (no quantity sent, so the default of 1 applies). The 201 body, a list holding one
    // booking, is unwrapped to that booking; an error body (a ProblemDetail) is returned as it is.
    protected Response signUp(int eventId, int userId, String paymentMethodId) {
        Response response = signUp(eventId, userId, paymentMethodId, null);
        JsonNode body = response.body();
        return body != null && body.isArray() && body.size() == 1
                ? new Response(response.status(), body.get(0))
                : response;
    }

    // Signup for several tickets: the raw response (on 201, a list with one booking per ticket)
    protected Response signUp(int eventId, int userId, String paymentMethodId, Integer quantity) {
        String method = paymentMethodId == null ? "" : "&paymentMethodId=" + paymentMethodId;
        String tickets = quantity == null ? "" : "&quantity=" + quantity;
        return call(HttpMethod.POST, "/events/" + eventId + "/signup?userId=" + userId + method + tickets, null,
                PARTICIPANT);
    }

    protected Response pay(int eventId, int bookingId, String paymentMethodId) {
        return call(HttpMethod.POST,
                "/events/" + eventId + "/bookings/" + bookingId + "/payment?paymentMethodId=" + paymentMethodId,
                null, PARTICIPANT);
    }

    protected Map<String, Object> bookingRow(int bookingId) {
        return jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
    }

    // Messages still waiting in the outbox for the broker
    protected List<Map<String, Object>> outboxRows() {
        return jdbc.queryForList("SELECT * FROM outbox_messages ORDER BY id");
    }

    protected int capacity(int eventId) {
        return jdbc.queryForObject("SELECT capacity FROM events WHERE id = ?", Integer.class, eventId);
    }

    protected static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    // ---------- RabbitMQ helpers ----------

    // Waits up to timeoutMillis for the next message on a capture queue; null if none arrives
    protected JsonNode receiveJson(String queue, long timeoutMillis) {
        Message message = rabbitTemplate.receive(queue, timeoutMillis);
        return message == null ? null : json.readTree(message.getBody());
    }

    protected void captureQueue(String queue, String exchange, String routingKey) {
        amqpAdmin.declareQueue(new Queue(queue, true, false, false));
        amqpAdmin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, exchange, routingKey, null));
        amqpAdmin.purgeQueue(queue, false);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
