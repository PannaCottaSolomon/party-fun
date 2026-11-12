package payment.config;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.ImmediateRequeueMessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The RabbitMQ topology owned (or used) by the Payment Service. Declared on startup; safe to run repeatedly.
 *
 * <p>The party.fun architecture has 4 exchanges (counting the built-in default exchange, which is never declared)
 * and exactly 6 queues. Payment declares 3 exchanges and only its own 2 queues; the 4 notification queues
 * ({@code notifications_service} and its 3 retry queues) belong to the Notification Service.
 *
 * <pre>
 *   Event Service --event.cancelled--> [party.events] --> [payments.refunds.requested] --> EventCancelledListener
 *       Payment refunds every paid payment of the event itself, one by one (no per-payment queue):
 *       refunded:                     --notify.refund-->      [notifications] --> notifications_service
 *       still failing after attempts: --payments.refunds.dlq--> [payments.refund.dlx] --> [payments.refunds.dlq]
 *                                     --notify.admin_alert--> [notifications] --> notifications_service
 *   Admin --HTTP--> Payment (/admin/refunds/...) --> Stripe; a recovered refund's DLQ message is acked (removed)
 * </pre>
 *
 * Failed refunds reach the DLQ by <b>application-managed routing</b>: Payment publishes one message per failed
 * payment to the dead-letter exchange. RabbitMQ does not dead-letter them by itself, because one event.cancelled
 * message stands for many payments and is acked once all of them are handled.
 */
@Configuration
public class RabbitConfig {

    private final PaymentProperties.Messaging messaging;

    public RabbitConfig(PaymentProperties properties) {
        this.messaging = properties.messaging();
    }

    /** Exchange 3 of 4: party.events (topic). The Event Service publishes event.cancelled on it. */
    @Bean
    public TopicExchange partyEventsExchange() {
        return ExchangeBuilder.topicExchange(messaging.exchange()).durable(true).build();
    }

    /**
     * Exchange 2 of 4: the Notification Service's {@code notifications} exchange (topic). Declared with the same
     * settings it uses, so declaring it from either service is harmless. Its queues are never declared here.
     */
    @Bean
    public TopicExchange notificationsExchange() {
        return ExchangeBuilder.topicExchange(messaging.notificationExchange()).durable(true).build();
    }

    /** Exchange 4 of 4: payments.refund.dlx (direct). Payment publishes each finally failed refund here. */
    @Bean
    public DirectExchange refundDeadLetterExchange() {
        return ExchangeBuilder.directExchange(messaging.refundDeadLetterExchange()).durable(true).build();
    }

    /** Queue 5 of 6: payments.refunds.requested, one message per cancelled event. */
    @Bean
    public Queue refundRequestedQueue() {
        return QueueBuilder.durable(messaging.refundRequestedQueue()).build();
    }

    @Bean
    public Binding eventCancelledBinding() {
        return BindingBuilder.bind(refundRequestedQueue())
                .to(partyEventsExchange())
                .with(messaging.eventCancelledRoutingKey());
    }

    /** Queue 6 of 6: payments.refunds.dlq, individually failed refunds awaiting Admin recovery. Nothing consumes it. */
    @Bean
    public Queue refundDeadLetterQueue() {
        return QueueBuilder.durable(messaging.refundDeadLetterQueue()).build();
    }

    @Bean
    public Binding refundDeadLetterBinding() {
        return BindingBuilder.bind(refundDeadLetterQueue())
                .to(refundDeadLetterExchange())
                .with(messaging.refundDeadLetterQueue());
    }

    /**
     * Listener container for event.cancelled. If handling fails unexpectedly (e.g. MySQL is briefly down), the
     * message is retried a few times with backoff and then <b>requeued</b>, never dropped: one message stands for a
     * whole event's refunds, and losing it would silently skip them. Re-handling is safe because refunds are
     * idempotent (REFUNDED payments are skipped, REFUND_PENDING ones resume with the same Stripe idempotency key).
     * Malformed messages never reach this: the listener logs and acks them.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory eventCancelledContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setPrefetchCount(1);
        factory.setAdviceChain(eventCancelledRetry());
        return factory;
    }

    static Advice eventCancelledRetry() {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(2)
                .backOffOptions(2_000, 2.0, 10_000)
                .recoverer(new ImmediateRequeueMessageRecoverer())
                .build();
    }
}
