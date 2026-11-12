package events.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import events.dto.EventCancelledMessage;
import events.entity.OutboxMessage;
import events.repository.OutboxMessageRepository;

/**
 * Unit tests for {@link Outbox}: a message is stored with the caller's transaction and published after the commit,
 * and its row is deleted only once the broker confirmed and routed it. Anything else keeps it, in order, with the
 * reason. The broker is a mock template whose confirms each test completes itself.
 */
@ExtendWith(MockitoExtension.class)
class OutboxTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Mock
    private OutboxMessageRepository repository;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private Outbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new Outbox(repository, rabbitTemplate, new JacksonJsonMessageConverter(),
                TransactionOperations.withoutTransaction(), Duration.ofMillis(200), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void enqueueStoresTheMessageAndPublishesItOnlyAfterTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        outbox.enqueue("party.events", "event.cancelled",
                new EventCancelledMessage(3, "cancel-3", "2026-10-29T10:00:00Z", "Event cancelled by the organiser"));

        ArgumentCaptor<OutboxMessage> stored = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(stored.capture());
        assertThat(stored.getValue().getExchange()).isEqualTo("party.events");
        assertThat(stored.getValue().getRoutingKey()).isEqualTo("event.cancelled");
        assertThat(stored.getValue().getPayload()).contains("\"eventId\":3", "\"requestId\":\"cancel-3\"");
        assertThat(stored.getValue().getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verifyNoInteractions(rabbitTemplate);  // a rollback never reaches afterCommit, so nothing is sent

        stubPending(withId(stored.getValue(), 1L));
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("party.events"), eq("event.cancelled"), sent.capture(),
                any(CorrelationData.class));
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).contains("\"eventId\":3");
        assertThat(sent.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(sent.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(sent.getValue().getMessageProperties().getMessageId()).isEqualTo("outbox-1");
        verify(repository).deleteById(1L);
    }

    @Test
    void confirmedMessagesAreDeletedOneByOne() {
        stubPending(message(1L), message(2L));
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        outbox.relay();

        verify(repository).deleteById(1L);
        verify(repository).deleteById(2L);
        verify(repository, never()).recordFailedAttempt(anyLong(), anyString());
    }

    @Test
    void anUnreachableBrokerKeepsEveryMessageInOrder() {
        stubPending(message(1L), message(2L));
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        outbox.relay();

        verify(repository).recordFailedAttempt(eq(1L), startsWith("org.springframework.amqp.AmqpConnectException"));
        verify(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void aMessageTheBrokerRefusesIsKeptWithTheReason() {
        stubPending(message(1L));
        brokerAnswers(correlation -> correlation.getFuture().complete(
                new CorrelationData.Confirm(false, "queue limit reached")));

        outbox.relay();

        verify(repository).recordFailedAttempt(1L, "the broker refused it: queue limit reached");
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void anUnroutableMessageIsKeptEvenThoughTheBrokerConfirmedIt() {
        stubPending(message(1L));
        brokerAnswers(correlation -> {
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "party.events",
                    "event.cancelled"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        outbox.relay();

        verify(repository).recordFailedAttempt(1L, "unroutable, no queue is bound for it (NO_ROUTE)");
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void noConfirmInTimeKeepsTheMessage() {
        stubPending(message(1L));
        brokerAnswers(correlation -> {
            // the broker never answers
        });

        outbox.relay();

        verify(repository).recordFailedAttempt(1L, "no confirm from the broker within PT0.2S");
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void aChannelLostWhileWaitingKeepsTheMessage() {
        stubPending(message(1L));
        brokerAnswers(correlation -> correlation.getFuture()
                .completeExceptionally(new IllegalStateException("channel closed")));

        outbox.relay();

        verify(repository).recordFailedAttempt(1L, "java.lang.IllegalStateException: channel closed");
    }

    @Test
    void aVeryLongReasonIsCutToTheColumnSize() {
        stubPending(message(1L));
        brokerAnswers(correlation -> correlation.getFuture().complete(
                new CorrelationData.Confirm(false, "x".repeat(400))));

        outbox.relay();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(repository).recordFailedAttempt(eq(1L), reason.capture());
        assertThat(reason.getValue()).hasSize(255);
    }

    @Test
    void anEmptyOutboxSendsNothing() {
        stubPending();

        outbox.relay();

        verifyNoInteractions(rabbitTemplate);
    }

    private void stubPending(OutboxMessage... messages) {
        when(repository.findTop100ByOrderByIdAsc()).thenReturn(List.of(messages));
    }

    // Each publish runs answer on its CorrelationData, as the broker's confirm (and return) would
    private void brokerAnswers(Consumer<CorrelationData> answer) {
        doAnswer(invocation -> {
            answer.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static OutboxMessage message(long id) {
        return withId(new OutboxMessage("party.events", "event.cancelled", "{\"eventId\":" + id + "}",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)), id);
    }

    private static OutboxMessage withId(OutboxMessage message, long id) {
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }
}
