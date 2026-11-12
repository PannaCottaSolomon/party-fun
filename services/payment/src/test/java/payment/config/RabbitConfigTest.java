package payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AbstractExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;

import payment.TestData;

/**
 * The RabbitMQ topology Payment declares, against the target architecture: 4 exchanges counting the built-in
 * default one (never declared) and 6 queues in total, of which Payment owns exactly 2.
 */
class RabbitConfigTest {

    private static final List<String> OBSOLETE = List.of("payment.refunds.process", "payments.refunds.process",
            "payment.refunds.reprocess", "payments.refunds.reprocess", "refund.reprocess.requested",
            "payments.refunded", "event.payments.refunded", "payment.refunds.dlx", "payment.refunds.dlq",
            "payment.refunds.requested");

    private final RabbitConfig config = new RabbitConfig(TestData.properties());

    @Test
    void exchangesHaveTheExactNamesAndTypes() {
        TopicExchange partyEvents = config.partyEventsExchange();
        TopicExchange notifications = config.notificationsExchange();
        DirectExchange dlx = config.refundDeadLetterExchange();

        assertThat(partyEvents.getName()).isEqualTo("party.events");
        assertThat(partyEvents.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(notifications.getName()).isEqualTo("notifications");
        assertThat(notifications.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(dlx.getName()).isEqualTo("payments.refund.dlx");
        assertThat(dlx.getType()).isEqualTo(ExchangeTypes.DIRECT);
        assertThat(List.of(partyEvents, notifications, dlx)).allSatisfy(exchange -> {
            assertThat(exchange.isDurable()).isTrue();
            assertThat(exchange.isAutoDelete()).isFalse();
        });
    }

    @Test
    void declaresExactlyThreeExchangesAndNeverTheDefaultOne() throws Exception {
        List<String> exchanges = beansOfType(Exchange.class).stream().map(e -> ((AbstractExchange) e).getName())
                .toList();

        assertThat(exchanges).containsExactlyInAnyOrder("party.events", "notifications", "payments.refund.dlx");
        assertThat(exchanges).doesNotContain("");
    }

    @Test
    void declaresExactlyItsOwnTwoQueues() throws Exception {
        List<String> queues = beansOfType(Queue.class).stream().map(q -> ((Queue) q).getName()).toList();

        // The other four of the six (notifications_service and its .retry.30s/.2m/.10m queues) belong to the
        // Notification Service and are never declared here.
        assertThat(queues).containsExactlyInAnyOrder("payments.refunds.requested", "payments.refunds.dlq");
    }

    @Test
    void eventCancelledFeedsTheRefundRequestQueue() {
        Queue queue = config.refundRequestedQueue();

        assertThat(queue.getName()).isEqualTo("payments.refunds.requested");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).isEmpty();
        assertBinding(config.eventCancelledBinding(), "party.events", "event.cancelled", "payments.refunds.requested");
    }

    @Test
    void dlqIsBoundToTheDeadLetterExchangeWithItsOwnNameAsTheKey() {
        Queue dlq = config.refundDeadLetterQueue();

        assertThat(dlq.getName()).isEqualTo("payments.refunds.dlq");
        assertThat(dlq.isDurable()).isTrue();
        // No TTL / max-length: failed refunds stay until an Admin recovers them.
        assertThat(dlq.getArguments()).isEmpty();
        assertBinding(config.refundDeadLetterBinding(), "payments.refund.dlx", "payments.refunds.dlq",
                "payments.refunds.dlq");
    }

    @Test
    void noQueueDeadLettersByItselfBecauseFailedRefundsArePublishedByTheApplication() throws Exception {
        for (Object queue : beansOfType(Queue.class)) {
            assertThat(((Queue) queue).getArguments()).doesNotContainKeys("x-dead-letter-exchange",
                    "x-dead-letter-routing-key");
        }
    }

    @Test
    void noObsoleteNameIsDeclaredOrBound() throws Exception {
        List<String> names = new ArrayList<>();
        for (Object bean : beansOfType(Object.class)) {
            if (bean instanceof Queue queue) {
                names.add(queue.getName());
            } else if (bean instanceof AbstractExchange exchange) {
                names.add(exchange.getName());
            } else if (bean instanceof Binding binding) {
                names.add(binding.getExchange());
                names.add(binding.getRoutingKey());
                names.add(binding.getDestination());
            }
        }
        assertThat(names).doesNotContainAnyElementsOf(OBSOLETE);
    }

    private List<Object> beansOfType(Class<?> type) throws Exception {
        List<Object> beans = new ArrayList<>();
        for (Method method : RabbitConfig.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class) && method.getParameterCount() == 0
                    && type.isAssignableFrom(method.getReturnType())) {
                beans.add(method.invoke(config));
            }
        }
        return beans;
    }

    private static void assertBinding(Binding binding, String exchange, String routingKey, String queue) {
        assertThat(binding.getExchange()).isEqualTo(exchange);
        assertThat(binding.getRoutingKey()).isEqualTo(routingKey);
        assertThat(binding.getDestination()).isEqualTo(queue);
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
    }
}
