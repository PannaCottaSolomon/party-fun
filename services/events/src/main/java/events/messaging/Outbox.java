package events.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import events.entity.OutboxMessage;
import events.repository.OutboxMessageRepository;

/*  Transactional outbox for the messages that must never be lost: event.cancelled, which the payment service's
    refunds of a cancelled event hang on. (Notifications stay best effort, see EventMessaging.)

    enqueue() stores the message in the outbox_messages table inside the caller's transaction, so it exists exactly
    when the cancellation committed, and publishes it right after the commit. relay(), also run by EventScheduler
    (events.outbox.relay-cron), publishes whatever is still stored. A row is deleted only once the broker has
    confirmed the message (publisher confirm) and routed it to a queue (a mandatory publish that was not returned).
    If the broker is down, refuses it, or no queue is bound yet (e.g. the payment service has never started), the
    row stays, with its attempts and last error, and is sent again on the next run.

    Delivery is at least once: a crash between the confirm and the delete, or two instances relaying at the same
    time, can publish a message twice. The payment service treats a repeated event.cancelled as a no-op. */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    // outbox_messages.last_error is varchar(255)
    private static final int MAX_ERROR_LENGTH = 255;

    private final OutboxMessageRepository repository;
    private final RabbitTemplate rabbitTemplate;         // own template: mandatory publishes and no retries
    private final MessageConverter messageConverter;     // the same JSON converter as every other message
    private final TransactionOperations newTransaction;  // relay() also runs after a commit, see inNewTransaction
    private final Duration confirmTimeout;
    private final Clock clock;

    // Production constructor: a template of its own on the shared (confirming) connection factory, so that the
    // mandatory flag and the absence of retries apply to outbox messages only
    @Autowired
    public Outbox(OutboxMessageRepository repository, ConnectionFactory connectionFactory,
            MessageConverter messageConverter, PlatformTransactionManager transactionManager,
            @Value("${events.outbox.confirm-timeout}") Duration confirmTimeout) {
        this(repository, mandatoryTemplate(connectionFactory), messageConverter, requiresNew(transactionManager),
                confirmTimeout, Clock.systemUTC());
    }

    // Full constructor: lets unit tests pass a mock template and run without transactions
    public Outbox(OutboxMessageRepository repository, RabbitTemplate rabbitTemplate,
            MessageConverter messageConverter, TransactionOperations newTransaction, Duration confirmTimeout,
            Clock clock) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
        this.newTransaction = newTransaction;
        this.confirmTimeout = confirmTimeout;
        this.clock = clock;
    }

    // Stores the message as part of the caller's transaction (there must be one) and publishes it once that
    // transaction has committed. Nothing is stored or sent if it rolls back.
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String exchange, String routingKey, Object message) {
        byte[] body = messageConverter.toMessage(message, new MessageProperties()).getBody();
        repository.save(new OutboxMessage(exchange, routingKey, new String(body, StandardCharsets.UTF_8),
                LocalDateTime.now(clock)));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relay();
            }
        });
    }

    // Publishes the stored messages, oldest first, and stops at the first one the broker does not take (it is most
    // likely down, or nothing is bound yet): that one and the rest wait for the next run, in order.
    // synchronized: a scheduled run and the publish after a commit never send the same row at the same time.
    public synchronized void relay() {
        List<OutboxMessage> pending = inNewTransaction(repository::findTop100ByOrderByIdAsc);
        for (OutboxMessage message : pending) {
            String failure = publish(message);
            if (failure != null) {
                String error = failure.length() > MAX_ERROR_LENGTH ? failure.substring(0, MAX_ERROR_LENGTH) : failure;
                inNewTransaction(() -> repository.recordFailedAttempt(message.getId(), error));
                log.warn("Outbox message {} ({} to {}) not delivered, kept for the next attempt ({} pending): {}",
                        message.getId(), message.getRoutingKey(), message.getExchange(), pending.size(), failure);
                return;
            }
            inNewTransaction(() -> {
                repository.deleteById(message.getId());
                return null;
            });
            log.info("Outbox message {} ({} to {}) delivered after {} failed attempt(s)", message.getId(),
                    message.getRoutingKey(), message.getExchange(), message.getAttempts());
        }
    }

    // Publishes one message and waits for the broker's confirm. Returns null once it is delivered, otherwise why not.
    private String publish(OutboxMessage stored) {
        String id = "outbox-" + stored.getId();
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(id);
        Message message = new Message(stored.getPayload().getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlation = new CorrelationData(id);
        try {
            rabbitTemplate.send(stored.getExchange(), stored.getRoutingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                return "the broker refused it: " + confirm.reason();
            }
            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                return "unroutable, no queue is bound for it (" + returned.getReplyText() + ")";
            }
            return null;
        } catch (AmqpException exception) {
            return exception.toString();
        } catch (TimeoutException exception) {
            return "no confirm from the broker within " + confirmTimeout;
        } catch (ExecutionException exception) {
            return exception.getCause().toString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "interrupted while waiting for the broker's confirm";
        }
    }

    // relay() also runs from afterCommit(), when the finished transaction's resources may still be bound to the
    // thread: without a new transaction its deletes would join that one and never be committed
    private <T> T inNewTransaction(Supplier<T> work) {
        return newTransaction.execute(status -> work.get());
    }

    private static RabbitTemplate mandatoryTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
