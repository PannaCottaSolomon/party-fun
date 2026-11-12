package payment.messaging;

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
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;

import payment.config.PaymentProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only class that reads {@code payments.refunds.dlq} (RabbitMQ owns the queue; there is no DLQ table in MySQL).
 *
 * <p>RabbitMQ cannot delete an arbitrary message by id, so a message is removed the only safe way: it is fetched
 * with <b>manual acknowledgement</b> ({@code basicGet} with autoAck off), which hides it from everyone else, and then
 * either <b>acknowledged</b> (removed, after its refund was recovered) or <b>negatively acknowledged with requeue</b>
 * (put back unchanged). Unrelated messages fetched while looking for one payment's message are always put back
 * before any refund is attempted; they are never acknowledged. If the service stops while holding messages, the
 * broker returns them to the queue by itself.
 */
@Component("refundDeadLetterQueueAccess") // "refundDeadLetterQueue" is the Queue bean declared in RabbitConfig
public class RefundDeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(RefundDeadLetterQueue.class);
    private static final Duration MANAGEMENT_TIMEOUT = Duration.ofSeconds(3);

    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;
    private final String queue;
    private final String managementUrl;
    private final String managementAuth;
    private final HttpClient http;

    public RefundDeadLetterQueue(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper, PaymentProperties properties,
                                 @Value("${spring.rabbitmq.username:guest}") String username,
                                 @Value("${spring.rabbitmq.password:guest}") String password) {
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
        this.queue = properties.messaging().refundDeadLetterQueue();
        String url = properties.messaging().managementUrl();
        this.managementUrl = url == null ? "" : url.replaceAll("/+$", "");
        this.managementAuth = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(MANAGEMENT_TIMEOUT).build();
    }

    /**
     * The DLQ's message counts. {@code ready} messages wait for an Admin; {@code unacknowledged} ones are being
     * handled right now (an Admin recovery in progress).
     *
     * <p>{@code ready} always comes over AMQP, which is exact and immediate. The RabbitMQ management API refreshes its
     * statistics only every few seconds, so right after a recovery removed a message it would still count it. Only
     * {@code unacknowledged}, which AMQP cannot report, comes from the management API (it may lag by those few
     * seconds); without the management API it is {@code null}.
     */
    public DlqStats stats() {
        long ready = readyCount();
        if (!managementUrl.isEmpty()) {
            try {
                long unacked = unacknowledgedFromManagementApi();
                return new DlqStats(queue, ready, unacked, ready + unacked, "management-api");
            } catch (IOException | RuntimeException e) {
                log.warn("RabbitMQ management API unavailable ({}); counting the DLQ over AMQP only",
                        e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new DlqStats(queue, ready, null, ready, "amqp");
    }

    /** Messages waiting in the DLQ right now (exact; messages held by a recovery in progress are not included). */
    public long readyCount() {
        Long ready = rabbitTemplate.execute(channel -> (long) channel.queueDeclarePassive(queue).getMessageCount());
        return ready == null ? 0 : ready;
    }

    long unacknowledgedFromManagementApi() throws IOException, InterruptedException {
        URI uri = URI.create(managementUrl + "/api/queues/%2F/" + URLEncoder.encode(queue, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(MANAGEMENT_TIMEOUT)
                .header("Authorization", managementAuth).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return parseUnacknowledged(response.body());
    }

    /** {@code messages_unacknowledged} from the management API's queue JSON. */
    long parseUnacknowledged(String body) {
        return jsonMapper.readTree(body).path("messages_unacknowledged").asLong(0);
    }

    /**
     * Looks at up to {@code limit} waiting messages without removing any: each is fetched unacknowledged and put back.
     * (Fetched-and-requeued messages are marked "redelivered" by RabbitMQ; their content is unchanged.)
     */
    public List<DlqEntry> peek(int limit) {
        List<DlqEntry> entries = rabbitTemplate.execute(channel -> {
            List<DlqEntry> found = new ArrayList<>();
            List<Long> tags = new ArrayList<>();
            try {
                int depth = channel.queueDeclarePassive(queue).getMessageCount();
                for (int i = 0; i < Math.min(depth, limit); i++) {
                    GetResponse response = channel.basicGet(queue, false);
                    if (response == null) {
                        break;
                    }
                    tags.add(response.getEnvelope().getDeliveryTag());
                    found.add(entry(response));
                }
            } finally {
                requeueAll(channel, tags);
            }
            return found;
        });
        return entries == null ? List.of() : entries;
    }

    /**
     * Holds every waiting message of one payment while {@code work} runs, then removes or keeps them.
     *
     * <ol>
     *   <li>Scans the queue (at most its depth at the start) with manual acknowledgement, keeping the messages of
     *       {@code paymentId}.</li>
     *   <li>Puts every unrelated message back at once, before {@code work} runs.</li>
     *   <li>Runs {@code work} with the number of messages held (0 if the payment has none in the DLQ).</li>
     *   <li>Acknowledges (removes) the held messages if {@code work} says so, otherwise puts them back. On an
     *       exception they are put back too.</li>
     * </ol>
     */
    public <T> T settle(int paymentId, IntFunction<Settlement<T>> work) {
        return rabbitTemplate.execute(channel -> {
            List<Long> held = new ArrayList<>();
            List<Long> unrelated = new ArrayList<>();
            boolean remove = false;
            try {
                int depth = channel.queueDeclarePassive(queue).getMessageCount();
                for (int i = 0; i < depth; i++) {
                    GetResponse response = channel.basicGet(queue, false);
                    if (response == null) {
                        break;
                    }
                    long tag = response.getEnvelope().getDeliveryTag();
                    Integer id = entry(response).paymentId();
                    if (id != null && id == paymentId) {
                        held.add(tag);
                    } else {
                        unrelated.add(tag);
                    }
                }
                requeueAll(channel, unrelated);
                unrelated.clear();

                Settlement<T> settlement = work.apply(held.size());
                remove = settlement.removeFromQueue();
                return settlement.result();
            } finally {
                requeueAll(channel, unrelated);
                for (long tag : held) {
                    if (remove) {
                        channel.basicAck(tag, false);
                    } else {
                        channel.basicNack(tag, false, true);
                    }
                }
                if (!held.isEmpty()) {
                    log.info("Payment {}: {} DLQ message(s) {}", paymentId, held.size(),
                            remove ? "acknowledged and removed" : "kept in the queue");
                }
            }
        });
    }

    private static void requeueAll(Channel channel, List<Long> tags) throws IOException {
        for (long tag : tags) {
            channel.basicNack(tag, false, true);
        }
    }

    /** Reads a DLQ message: the payment-id header first, otherwise the JSON body (also older message formats). */
    DlqEntry entry(GetResponse response) {
        Map<String, Object> headers = response.getProps() == null ? null : response.getProps().getHeaders();
        Integer headerId = headers == null ? null : toInt(headers.get(PaymentEventPublisher.PAYMENT_ID_HEADER));
        FailedRefundMessage message = null;
        try {
            message = jsonMapper.readValue(response.getBody(), FailedRefundMessage.class);
        } catch (JacksonException e) {
            log.warn("A message in {} is not valid failed-refund JSON; it is left in the queue", queue);
        }
        Integer id = headerId != null ? headerId : message == null ? null : message.paymentId();
        return new DlqEntry(id, message, Boolean.TRUE.equals(response.getEnvelope().isRedeliver()));
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.valueOf(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Counts for {@code GET /admin/refunds/dlq/count}. {@code unacknowledged} is null when only AMQP was available. */
    public record DlqStats(String queue, long ready, Long unacknowledged, long total, String source) {
    }

    /** One waiting message: its payment (null if unreadable) and its content. */
    public record DlqEntry(Integer paymentId, FailedRefundMessage message, boolean redelivered) {
    }

    /** What to do with the held messages, and the result to return. */
    public record Settlement<T>(boolean removeFromQueue, T result) {

        public static <T> Settlement<T> remove(T result) {
            return new Settlement<>(true, result);
        }

        public static <T> Settlement<T> keep(T result) {
            return new Settlement<>(false, result);
        }
    }
}
