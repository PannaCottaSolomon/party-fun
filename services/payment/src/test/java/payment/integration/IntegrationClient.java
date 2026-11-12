package payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.stripe.net.Webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Black-box client for the integration tests. Talks only HTTP: the Payment Service REST API, RabbitMQ's
 * management API (to publish refund requests and read what the service published) and WireMock's admin API, where
 * WireMock stands in for api.stripe.com (ci/compose.test.yaml): default answers come from ci/wiremock/mappings, and
 * the tests add per-PaymentIntent answers to set up Stripe's side of a case, and read WireMock's request log to see
 * what the service sent to Stripe. It never starts Spring or touches the database, so the tests exercise the real
 * running container.
 *
 * <p>Configured by environment variables (set by ci/compose.test.yaml):
 * {@code PAYMENT_URL}, {@code RABBITMQ_API}, {@code RABBITMQ_USER}, {@code RABBITMQ_PASSWORD},
 * {@code JWT_SECRET} (the JWT issuer's shared secret: Admin calls send an {@code ADMIN} token),
 * {@code ADMIN_API_KEY} (the operator fallback, sent as {@code X-Admin-Api-Key}),
 * {@code SERVICE_API_KEY} (sent as {@code X-Service-Api-Key}, as the Event Service does for a compensation refund),
 * {@code WIREMOCK_URL} (the Stripe stand-in) and
 * {@code WEBHOOK_SECRET} (the throw-away signing secret the Payment container verifies webhooks with).
 */
final class IntegrationClient {

    static final String DLQ = "payments.refunds.dlq";
    static final String OBSERVER_QUEUE = "it.payment.observer";

    private static IntegrationClient instance;

    private final String paymentUrl = env("PAYMENT_URL", "http://localhost:8080");
    private final String rabbitApi = env("RABBITMQ_API", "http://localhost:15672");
    private final String jwtSecret = env("JWT_SECRET", "it-jwt-secret-only-for-integration-tests-32b");
    private final String adminApiKey = env("ADMIN_API_KEY", "it-admin-key");
    private final String serviceApiKey = env("SERVICE_API_KEY", "it-service-key");
    private final String wiremockUrl = env("WIREMOCK_URL", "http://localhost:8089");
    // PaymentIntents refunded directly "at Stripe" by refundAtStripe (they count as one refund each).
    private final Set<String> directlyRefunded = new HashSet<>();
    private final String webhookSecret = env("WEBHOOK_SECRET", "it-webhook-signing-secret");
    private final String rabbitAuth = "Basic " + Base64.getEncoder().encodeToString(
            (env("RABBITMQ_USER", "guest") + ":" + env("RABBITMQ_PASSWORD", "guest")).getBytes(StandardCharsets.UTF_8));
    // HTTP/1.1: the JDK default (HTTP/2 with an h2c upgrade) makes RabbitMQ's management API drop requests with bodies.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final JsonMapper json = JsonMapper.builder().build();
    // Reading a queue through the management API removes the messages, so keep what we have read.
    private final Map<String, List<Message>> buffers = new HashMap<>();

    private IntegrationClient() {
    }

    /** Shared by all IT classes: waits for the service once and sets up the observer queue once. */
    static synchronized IntegrationClient get() {
        if (instance == null) {
            IntegrationClient client = new IntegrationClient();
            client.awaitHealthy();
            client.declareObserverQueue();
            instance = client;
        }
        return instance;
    }

    // ---------------------------------------------------------------- HTTP responses

    /** An HTTP response with its body parsed as JSON (or null if it was not JSON). */
    record Response(int status, String text, Object body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            assertThat(body).as("JSON object body of: %s", text).isInstanceOf(Map.class);
            return (Map<String, Object>) body;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            assertThat(body).as("JSON array body of: %s", text).isInstanceOf(List.class);
            return (List<Map<String, Object>>) body;
        }

        Object field(String name) {
            return map().get(name);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> mapField(String name) {
            assertThat(field(name)).as("JSON object '%s' in: %s", name, text).isInstanceOf(Map.class);
            return (Map<String, Object>) field(name);
        }
    }

    /** A message read from a RabbitMQ queue. */
    record Message(String routingKey, String exchange, Object body, Map<String, Object> headers) {

        /**
         * The first {@code x-death} entry RabbitMQ adds when it dead-letters a message (source queue, reason, …),
         * or an empty map if the message was not dead-lettered.
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> death() {
            return headers.get("x-death") instanceof List<?> deaths && !deaths.isEmpty()
                    && deaths.get(0) instanceof Map<?, ?> first ? (Map<String, Object>) first : Map.of();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return body instanceof Map<?, ?> ? (Map<String, Object>) body : Map.of();
        }
    }

    // ---------------------------------------------------------------- Payment Service API

    Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path)).GET());
    }

    Response post(String path, Object body) {
        return postRaw(path, body == null ? "" : json.writeValueAsString(body));
    }

    Response postRaw(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** An access token like the JWT issuer's, signed with the shared secret, for the given role. */
    String token(String role) {
        return payment.TestTokens.token(jwtSecret, role, java.time.Instant.now().plusSeconds(900), null);
    }

    /** POST to an Admin endpoint as an Admin (Authorization: Bearer <ADMIN token>), with no body. */
    Response adminPost(String path) {
        return postAs("Bearer " + token("ADMIN"), path);
    }

    /** GET an Admin endpoint as an Admin. */
    Response adminGet(String path) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .header("Authorization", "Bearer " + token("ADMIN"))
                .GET());
    }

    /** GET with a token of another role (e.g. PARTICIPANT), to check it is refused. */
    Response getWithRole(String role, String path) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .header("Authorization", "Bearer " + token(role))
                .GET());
    }

    /** POST with the operator fallback key (X-Admin-Api-Key) instead of a token. */
    Response operatorKeyPost(String path) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Admin-Api-Key", adminApiKey)
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private Response postAs(String authorization, String path) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** POST with the internal service key (X-Service-Api-Key), as another microservice would, and no body. */
    Response servicePost(String path) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Service-Api-Key", serviceApiKey)
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    Response request(String method, String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    Map<String, Object> createBooking(int userId, int eventId, double totalAmount) {
        Response response = post("/bookings", Map.of(
                "userId", userId, "eventId", eventId, "quantity", 2, "totalAmount", totalAmount));
        assertThat(response.status()).as(response.text()).isEqualTo(201);
        return response.map();
    }

    Map<String, Object> createBooking(int userId, int eventId) {
        return createBooking(userId, eventId, 40.00);
    }

    /** Pays for a booking; {@code paymentMethodId} null leaves the PaymentIntent pending. */
    Map<String, Object> pay(Object bookingId, String paymentMethodId) {
        Map<String, Object> body = new HashMap<>();
        body.put("bookingId", bookingId);
        if (paymentMethodId != null) {
            body.put("paymentMethodId", paymentMethodId);
        }
        Response response = post("/payments", body);
        assertThat(response.status()).as(response.text()).isEqualTo(201);
        return response.map();
    }

    /**
     * The Event Service's synchronous checkout (POST /payments/checkout), with the participant/event snapshot that
     * lets Payment send notify.refund later. 2 tickets at 20.00 = 40.00 SGD. Returns the HTTP response.
     */
    Response checkout(String eventBookingId, int userId, int eventId, String paymentMethodId) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventBookingId", eventBookingId);
        body.put("eventId", eventId);
        body.put("userId", userId);
        body.put("quantity", 2);
        body.put("ticketPrice", 20.00);
        body.put("participantEmail", "user" + userId + "@smu.edu.sg");
        body.put("participantName", "User " + userId);
        body.put("eventTitle", "IT event " + eventId);
        if (paymentMethodId != null) {
            body.put("paymentMethodId", paymentMethodId);
        }
        return post("/payments/checkout", body);
    }

    /** Pays for tickets through checkout; returns {booking, payment}. */
    List<Map<String, Object>> paidBooking(int userId, int eventId, String paymentMethodId) {
        Response response = checkout("evb-" + randomId(), userId, eventId, paymentMethodId);
        assertThat(response.status()).as(response.text()).isEqualTo(201);
        Map<String, Object> payment = response.map();
        assertThat(payment.get("status")).isEqualTo("PAID");
        Map<String, Object> booking = get("/bookings/" + payment.get("bookingId")).map();
        return List.of(booking, payment);
    }

    String bookingStatus(Object bookingId) {
        return (String) get("/bookings/" + bookingId).field("status");
    }

    String paymentStatus(Object paymentId) {
        return (String) get("/payments/" + paymentId).field("status");
    }

    /** Random IDs so tests never collide, even when re-run against the same database. */
    static int randomId() {
        return ThreadLocalRandom.current().nextInt(100_000, 999_999_999);
    }

    static Map<String, Object> stripeEvent(String type, Map<String, Object> object) {
        return Map.of("id", "evt_it_" + System.nanoTime(), "type", type, "livemode", false,
                "data", Map.of("object", object));
    }

    // ---------------------------------------------------------------- Stripe webhooks and Stripe (WireMock)

    /**
     * Sends a Stripe event to POST /payments/webhook/stripe with a valid {@code Stripe-Signature}, computed exactly
     * like Stripe does (HMAC-SHA256 of "timestamp.body") with the secret the Payment container is configured with.
     * Stripe cannot reach this Docker network, so the tests play Stripe's part for webhooks.
     */
    Response webhook(Map<String, Object> event) {
        String body = json.writeValueAsString(event);
        long timestamp = Webhook.Util.getTimeNow();
        String signature;
        try {
            signature = Webhook.Util.computeHmacSha256(webhookSecret, timestamp + "." + body);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return send(HttpRequest.newBuilder(URI.create(paymentUrl + "/payments/webhook/stripe"))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", "t=" + timestamp + ",v1=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /**
     * Makes Stripe show the PaymentIntent as already refunded, as if it had been refunded directly in the Stripe
     * dashboard behind the Payment Service's back: the service's own refund then gets {@code charge_already_refunded}
     * (a permanent error), and Stripe's refund list for it shows that succeeded refund (what Admin recovery finds).
     * Returns the refund id.
     */
    String refundAtStripe(String paymentIntentId) {
        String refundId = "re_direct_" + paymentIntentId;
        stub(Map.of("priority", 1,
                "request", Map.of("method", "POST", "urlPath", "/v1/refunds",
                        "formParameters", Map.of("payment_intent", Map.of("equalTo", paymentIntentId))),
                "response", jsonResponse(400, Map.of("error", Map.of("type", "invalid_request_error",
                        "code", "charge_already_refunded",
                        "message", "Charge for " + paymentIntentId + " has already been refunded.")))));
        stripeShowsRefund(paymentIntentId, refundId, "succeeded");
        directlyRefunded.add(paymentIntentId);
        return refundId;
    }

    /** Makes Stripe's refund list for the PaymentIntent show this refund (Stripe's actual state, for recovery). */
    void stripeShowsRefund(String paymentIntentId, String refundId, String status) {
        stub(Map.of("priority", 1,
                "request", Map.of("method", "GET", "urlPath", "/v1/refunds",
                        "queryParameters", Map.of("payment_intent", Map.of("equalTo", paymentIntentId))),
                "response", jsonResponse(200, Map.of("object", "list", "url", "/v1/refunds", "has_more", false,
                        "data", List.of(Map.of("id", refundId, "object", "refund", "status", status,
                                "payment_intent", paymentIntentId))))));
    }

    /**
     * Makes Stripe report the PaymentIntent as succeeded, as if Stripe.js had confirmed it in the browser. No webhook
     * reaches the Payment Service in these tests, so it only learns about it by syncing.
     */
    void confirmAtStripe(String paymentIntentId, String paymentMethodId) {
        stub(Map.of("priority", 1,
                "request", Map.of("method", "GET", "urlPath", "/v1/payment_intents/" + paymentIntentId),
                "response", jsonResponse(200, Map.of("id", paymentIntentId, "object", "payment_intent",
                        "status", "succeeded", "payment_method", paymentMethodId,
                        "payment_method_types", List.of("card")))));
    }

    /**
     * Makes Stripe fail the PaymentIntent's next {@code failures} refund requests with a 500 (a transient error the
     * real sandbox can't produce on demand), then accept the refund.
     */
    void stripeFailsRefundsTransiently(String paymentIntentId, int failures) {
        String scenario = "refund-" + paymentIntentId;
        for (int i = 0; i <= failures; i++) {
            String state = i == 0 ? "Started" : "failed-" + i;
            Map<String, Object> response = i < failures
                    ? jsonResponse(500, Map.of("error", Map.of("type", "api_error", "message", "Stripe is down")))
                    : jsonResponse(200, Map.of("id", "re_after_retries_" + paymentIntentId, "object", "refund",
                            "status", "succeeded", "payment_intent", paymentIntentId));
            Map<String, Object> mapping = new HashMap<>(Map.of("priority", 1,
                    "scenarioName", scenario, "requiredScenarioState", state,
                    "request", Map.of("method", "POST", "urlPath", "/v1/refunds",
                            "formParameters", Map.of("payment_intent", Map.of("equalTo", paymentIntentId))),
                    "response", response));
            if (i < failures) {
                mapping.put("newScenarioState", "failed-" + (i + 1));
            }
            stub(mapping);
        }
    }

    /**
     * How many refunds Stripe made for a PaymentIntent (to prove nothing was refunded twice): the refund requests
     * Stripe accepted, counted once per idempotency key (Stripe returns the same refund for a repeated key), plus a
     * refund made directly at Stripe by {@link #refundAtStripe}.
     */
    int stripeRefundCount(String paymentIntentId) {
        return refundRequests(paymentIntentId).stream()
                .filter(request -> ((Number) request.get("status")).intValue() == 200)
                .map(request -> request.get("idempotencyKey"))
                .distinct()
                .toList().size() + (directlyRefunded.contains(paymentIntentId) ? 1 : 0);
    }

    /** Every refund request the service sent to Stripe for a PaymentIntent: its idempotency key and Stripe's status. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> refundRequests(String paymentIntentId) {
        Response journal = send(HttpRequest.newBuilder(URI.create(wiremockUrl + "/__admin/requests")).GET());
        assertThat(journal.status()).as("WireMock request log: %s", journal.status()).isEqualTo(200);
        List<Map<String, Object>> found = new ArrayList<>();
        for (Object item : (List<Object>) journal.field("requests")) {
            Map<String, Object> event = (Map<String, Object>) item;
            Map<String, Object> request = (Map<String, Object>) event.get("request");
            String body = String.valueOf(request.get("body"));
            if ("POST".equals(request.get("method")) && "/v1/refunds".equals(request.get("url"))
                    && body.contains("payment_intent=" + encode(paymentIntentId))) {
                Map<String, Object> headers = (Map<String, Object>) request.get("headers");
                Map<String, Object> response = (Map<String, Object>) event.get("response");
                found.add(Map.of("idempotencyKey", String.valueOf(headers.get("Idempotency-Key")),
                        "status", response.get("status")));
            }
        }
        Collections.reverse(found); // WireMock lists the newest request first
        return found;
    }

    private void stub(Map<String, Object> mapping) {
        Response response = send(HttpRequest.newBuilder(URI.create(wiremockUrl + "/__admin/mappings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapping))));
        assertThat(response.status()).as("WireMock stub: %s", response.text()).isEqualTo(201);
    }

    private Map<String, Object> jsonResponse(int status, Map<String, Object> body) {
        return Map.of("status", status, "headers", Map.of("Content-Type", "application/json"),
                "body", json.writeValueAsString(body));
    }

    // ---------------------------------------------------------------- RabbitMQ management API

    void publish(String exchange, String routingKey, Object payload) {
        assertThat(publishAndReportRouted(exchange, routingKey, payload))
                .as("message to %s/%s was routed", exchange, routingKey).isTrue();
    }

    /** Publishes and returns whether RabbitMQ routed the message to at least one queue. */
    boolean publishAndReportRouted(String exchange, String routingKey, Object payload) {
        return publishAndReportRouted(exchange, routingKey, payload, Map.of());
    }

    /** Publishes with extra AMQP headers (e.g. the {@code __TypeId__} header Spring's JSON converter adds). */
    boolean publishAndReportRouted(String exchange, String routingKey, Object payload, Map<String, Object> headers) {
        String body = payload instanceof String text ? text : json.writeValueAsString(payload);
        Response response = rabbit("POST", "/exchanges/%2F/" + exchange + "/publish", Map.of(
                "properties", Map.of("content_type", "application/json", "delivery_mode", 2, "headers", headers),
                "routing_key", routingKey,
                "payload", body,
                "payload_encoding", "string"));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        return Boolean.TRUE.equals(response.field("routed"));
    }

    /**
     * The messages currently in the refund DLQ that match {@code predicate}, read WITHOUT removing them (the
     * management API fetches and requeues them), so the Payment Service's Admin recovery can still find them.
     */
    List<Message> dlq(Predicate<Message> predicate) {
        Response response = rabbit("POST", "/queues/%2F/" + encode(DLQ) + "/get",
                Map.of("count", 500, "ackmode", "ack_requeue_true", "encoding", "auto"));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        List<Message> found = new ArrayList<>();
        for (Map<String, Object> raw : response.list()) {
            Message message = new Message((String) raw.get("routing_key"), (String) raw.get("exchange"),
                    parse((String) raw.get("payload")), headers(raw));
            if (predicate.test(message)) {
                found.add(message);
            }
        }
        return found;
    }

    /** Polls the DLQ (without removing anything) until at least {@code count} matching messages are there. */
    List<Message> waitForDlq(Predicate<Message> predicate, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<Message> found = dlq(predicate);
            if (found.size() >= count || System.nanoTime() > deadline) {
                return found;
            }
            sleep(500);
        }
    }

    List<Message> waitForDlq(Predicate<Message> predicate) {
        return waitForDlq(predicate, 1, Duration.ofSeconds(20));
    }

    /** All messages read so far from {@code queue} (reads any new ones first) that match {@code predicate}. */
    synchronized List<Message> messages(String queue, Predicate<Message> predicate) {
        Response response = rabbit("POST", "/queues/%2F/" + encode(queue) + "/get",
                Map.of("count", 500, "ackmode", "ack_requeue_false", "encoding", "auto"));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        List<Message> buffer = buffers.computeIfAbsent(queue, q -> new ArrayList<>());
        for (Map<String, Object> raw : response.list()) {
            String payload = (String) raw.get("payload");
            buffer.add(new Message((String) raw.get("routing_key"), (String) raw.get("exchange"), parse(payload),
                    headers(raw)));
        }
        return buffer.stream().filter(predicate).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> headers(Map<String, Object> raw) {
        return raw.get("properties") instanceof Map<?, ?> properties
                && properties.get("headers") instanceof Map<?, ?> headers ? (Map<String, Object>) headers : Map.of();
    }

    List<Message> messages(String queue) {
        return messages(queue, m -> true);
    }

    /** Polls until at least {@code count} matching messages were seen, or the timeout passes. */
    List<Message> waitFor(String queue, Predicate<Message> predicate, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<Message> found = messages(queue, predicate);
            if (found.size() >= count || System.nanoTime() > deadline) {
                return found;
            }
            sleep(500);
        }
    }

    List<Message> waitFor(String queue, Predicate<Message> predicate) {
        return waitFor(queue, predicate, 1, Duration.ofSeconds(20));
    }

    /**
     * Messages about one payment: DLQ/refund requests use paymentId, admin alerts details.payment_id, and
     * notify.refund its deterministic notification_id refund-payment-{paymentId}.
     */
    static Predicate<Message> forPayment(Object paymentId) {
        return m -> paymentId.equals(m.map().get("paymentId"))
                || (m.map().get("details") instanceof Map<?, ?> details && paymentId.equals(details.get("payment_id")))
                || ("refund-payment-" + paymentId).equals(m.map().get("notification_id"));
    }

    static Predicate<Message> routedAs(String routingKey) {
        return m -> routingKey.equals(m.routingKey());
    }

    // ---------------------------------------------------------------- helpers

    static boolean waitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(500);
        }
        return condition.getAsBoolean();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void awaitHealthy() {
        boolean healthy = waitUntil(() -> {
            try {
                return get("/health").status() == 200;
            } catch (IllegalStateException e) {
                return false;
            }
        }, Duration.ofSeconds(120));
        assertThat(healthy).as("Payment Service healthy at %s", paymentUrl).isTrue();
    }

    private void declareObserverQueue() {
        // The Payment Service declares party.events and notifications on startup (it is healthy by now).
        rabbit("PUT", "/queues/%2F/" + OBSERVER_QUEUE, Map.of("durable", false, "auto_delete", false));
        bind("notifications", "notify.refund");
        bind("notifications", "notify.admin_alert");
    }

    /** The RabbitMQ management API's view of a queue ({@code durable}, {@code arguments}, …). */
    Response queue(String name) {
        return rabbitGet("/queues/%2F/" + encode(name));
    }

    /** Every binding into a queue ({@code source} exchange, {@code routing_key}). */
    Response queueBindings(String name) {
        return rabbitGet("/queues/%2F/" + encode(name) + "/bindings");
    }

    private Response rabbitGet(String path) {
        return send(HttpRequest.newBuilder(URI.create(rabbitApi + "/api" + path))
                .header("Authorization", rabbitAuth)
                .GET());
    }

    private void bind(String exchange, String routingKey) {
        Response response = rabbit("POST", "/bindings/%2F/e/" + exchange + "/q/" + OBSERVER_QUEUE,
                Map.of("routing_key", routingKey));
        assertThat(response.status()).as(response.text()).isEqualTo(201);
    }

    private Response rabbit(String method, String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(rabbitApi + "/api" + path))
                .header("Authorization", rabbitAuth)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), parse(response.body()));
        } catch (IOException e) {
            throw new IllegalStateException("HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readValue(text, Object.class);
        } catch (JacksonException e) {
            return text;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback.replaceAll("/+$", "") : value.replaceAll("/+$", "");
    }
}
