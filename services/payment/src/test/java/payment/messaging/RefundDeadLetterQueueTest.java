package payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;

import payment.TestData;
import payment.messaging.RefundDeadLetterQueue.DlqStats;
import payment.messaging.RefundDeadLetterQueue.Settlement;
import tools.jackson.databind.json.JsonMapper;

/**
 * The DLQ is only ever changed by acknowledging the held messages of the payment being recovered; unrelated messages
 * are put back (never acknowledged) before the recovery runs. Uses a mocked RabbitMQ channel.
 */
@ExtendWith(MockitoExtension.class)
class RefundDeadLetterQueueTest {

    private static final String QUEUE = "payments.refunds.dlq";

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Channel channel;
    @Mock
    private AMQP.Queue.DeclareOk declareOk;

    private final JsonMapper json = JsonMapper.builder().build();
    private RefundDeadLetterQueue dlq;
    private final List<GetResponse> waiting = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        dlq = new RefundDeadLetterQueue(rabbitTemplate, json, TestData.properties(), "guest", "guest");
        lenient().when(rabbitTemplate.execute(any(ChannelCallback.class))).thenAnswer(invocation ->
                ((ChannelCallback<Object>) invocation.getArgument(0)).doInRabbit(channel));
    }

    /** Puts messages in the mocked queue, in order: one per payment id (null = unreadable body). */
    private void queue(Integer... paymentIds) throws Exception {
        long tag = 1;
        for (Integer paymentId : paymentIds) {
            byte[] body = paymentId == null ? "not json".getBytes(StandardCharsets.UTF_8)
                    : ("{\"paymentId\":" + paymentId + ",\"refundAmountCents\":4000,\"retryCount\":3}")
                            .getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().headers(Map.of()).build();
            waiting.add(new GetResponse(new Envelope(tag++, false, "payments.refund.dlx", QUEUE), props, body, 0));
        }
        when(channel.queueDeclarePassive(QUEUE)).thenReturn(declareOk);
        when(declareOk.getMessageCount()).thenReturn(waiting.size());
        var stub = when(channel.basicGet(QUEUE, false));
        for (GetResponse response : waiting) {
            stub = stub.thenReturn(response);
        }
        stub.thenReturn(null);
    }

    @Test
    void successfulRecoveryAcknowledgesOnlyThatPaymentsMessagesAndPutsTheOthersBackFirst() throws Exception {
        queue(101, 102, 101, 103);
        List<Integer> heldSeen = new ArrayList<>();

        String result = dlq.settle(101, held -> {
            heldSeen.add(held);
            return Settlement.remove("refunded");
        });

        assertThat(result).isEqualTo("refunded");
        assertThat(heldSeen).containsExactly(2); // the original message and a duplicate
        InOrder order = inOrder(channel);
        // Unrelated messages (tags 2 and 4) go back before the recovery runs, and are never acknowledged.
        order.verify(channel).basicNack(2L, false, true);
        order.verify(channel).basicNack(4L, false, true);
        order.verify(channel).basicAck(1L, false);
        order.verify(channel).basicAck(3L, false);
        verify(channel, never()).basicAck(eq(2L), anyBoolean());
        verify(channel, never()).basicAck(eq(4L), anyBoolean());
    }

    @Test
    void failedRecoveryPutsTheMessageBackUnchanged() throws Exception {
        queue(101, 102);

        dlq.settle(101, held -> Settlement.keep("still failing"));

        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void anExceptionDuringRecoveryPutsEverythingBack() throws Exception {
        queue(101, 102);

        assertThatThrownBy(() -> dlq.settle(101, held -> {
            throw new IllegalStateException("database unavailable");
        })).isInstanceOf(IllegalStateException.class);

        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void paymentWithoutADlqMessageHoldsNothing() throws Exception {
        queue(102, null);
        List<Integer> heldSeen = new ArrayList<>();

        dlq.settle(101, held -> {
            heldSeen.add(held);
            return Settlement.remove("ok");
        });

        assertThat(heldSeen).containsExactly(0);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true); // unreadable messages are kept, never dropped
    }

    @Test
    void peekReadsWithoutRemovingAnything() throws Exception {
        queue(101, 102);

        List<RefundDeadLetterQueue.DlqEntry> entries = dlq.peek(10);

        assertThat(entries).extracting(RefundDeadLetterQueue.DlqEntry::paymentId).containsExactly(101, 102);
        assertThat(entries.get(0).message().refundAmountCents()).isEqualTo(4000L);
        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void readsOnlyTheUnacknowledgedCountFromTheManagementApi() {
        long unacked = dlq.parseUnacknowledged(
                "{\"name\":\"payments.refunds.dlq\",\"messages\":3,\"messages_ready\":2,\"messages_unacknowledged\":1}");

        assertThat(unacked).isEqualTo(1);
    }

    @Test
    void takesTheReadyCountFromAmqpBecauseTheManagementApiLags() throws Exception {
        // Right after a recovery the management API's statistics can still count the removed message as ready;
        // the ready count therefore comes from AMQP, which is exact.
        RefundDeadLetterQueue stale = new RefundDeadLetterQueue(rabbitTemplate, json,
                propertiesWithManagementUrl("http://rabbitmq:15672"), "guest", "guest") {
            @Override
            long unacknowledgedFromManagementApi() {
                return 0;
            }
        };
        when(channel.queueDeclarePassive(QUEUE)).thenReturn(declareOk);
        when(declareOk.getMessageCount()).thenReturn(0);

        assertThat(stale.stats()).isEqualTo(new DlqStats(QUEUE, 0, 0L, 0, "management-api"));
        assertThat(stale.readyCount()).isZero();
    }

    @Test
    void fallsBackToAmqpWhenTheManagementApiIsUnreachable() throws Exception {
        RefundDeadLetterQueue noApi = new RefundDeadLetterQueue(rabbitTemplate, json, propertiesWithManagementUrl(
                "http://127.0.0.1:1"), "guest", "guest");
        when(channel.queueDeclarePassive(QUEUE)).thenReturn(declareOk);
        when(declareOk.getMessageCount()).thenReturn(2);

        DlqStats stats = noApi.stats();

        assertThat(stats).isEqualTo(new DlqStats(QUEUE, 2, null, 2, "amqp"));
        verify(channel, never()).basicGet(any(), anyBoolean());
    }

    private static payment.config.PaymentProperties propertiesWithManagementUrl(String url) {
        payment.config.PaymentProperties base = TestData.properties();
        payment.config.PaymentProperties.Messaging m = base.messaging();
        return new payment.config.PaymentProperties(base.refund(), new payment.config.PaymentProperties.Messaging(
                m.exchange(), m.eventCancelledRoutingKey(), m.refundRequestedQueue(), m.refundDeadLetterExchange(),
                m.refundDeadLetterQueue(), m.notificationExchange(), m.refundNotificationRoutingKey(),
                m.adminAlertRoutingKey(), url), base.adminAlert(), base.recovery());
    }
}
