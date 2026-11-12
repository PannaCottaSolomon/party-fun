package payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import payment.service.RefundService;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class EventCancelledListenerTest {

    @Mock
    private RefundService refundService;

    private EventCancelledListener listener;

    @BeforeEach
    void setUp() {
        listener = new EventCancelledListener(refundService, JsonMapper.builder().build());
    }

    private static Message message(String body) {
        // No content type on purpose: producers (e.g. Python pika) may not set one.
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    void validMessageQueuesRefundsForTheEvent() {
        listener.onEventCancelled(message(
                "{\"eventId\": 42, \"requestId\": \"cancel-42\", \"timestamp\": \"2026-10-06T10:00:00Z\", \"extra\": 1}"));

        ArgumentCaptor<RefundRequestedMessage> captor = ArgumentCaptor.forClass(RefundRequestedMessage.class);
        verify(refundService).refundEvent(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(42);
        assertThat(captor.getValue().requestId()).isEqualTo("cancel-42");
    }

    @Test
    void snakeCaseFieldsAreAccepted() {
        listener.onEventCancelled(message("{\"event_id\": 7, \"request_id\": \"r-7\"}"));

        ArgumentCaptor<RefundRequestedMessage> captor = ArgumentCaptor.forClass(RefundRequestedMessage.class);
        verify(refundService).refundEvent(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(7);
        assertThat(captor.getValue().requestId()).isEqualTo("r-7");
    }

    @Test
    void malformedJsonIsDroppedWithoutRetryOrDlq() {
        assertThatCode(() -> listener.onEventCancelled(message("not json"))).doesNotThrowAnyException();
        verifyNoInteractions(refundService);
    }

    @Test
    void messageWithoutEventIdIsDropped() {
        assertThatCode(() -> listener.onEventCancelled(message("{\"requestId\": \"x\"}")))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onEventCancelled(message("{\"eventId\": -1}"))).doesNotThrowAnyException();
        verifyNoInteractions(refundService);
    }

    @Test
    void acceptsTheEventServicesCurrentMessageShape() {
        // Exactly what services/events publishes (EventMessaging.publishEventCancelled: EventCancelledMessage through
        // Spring's JSON converter, so with a __TypeId__ header).
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setHeader("__TypeId__", "events.dto.EventCancelledMessage");
        listener.onEventCancelled(new Message("""
                {"eventId":42,"requestId":"cancel-42","timestamp":"2026-10-18T14:25:30.123Z",
                 "reason":"Event cancelled by the organiser"}"""
                .getBytes(StandardCharsets.UTF_8), properties));

        ArgumentCaptor<RefundRequestedMessage> captor = ArgumentCaptor.forClass(RefundRequestedMessage.class);
        verify(refundService).refundEvent(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(42);
        assertThat(captor.getValue().requestId()).isEqualTo("cancel-42");
    }

    @Test
    void toleratesTheOlderEventServiceDtoShape() {
        // An earlier version of services/events sent EventNotificationMessageDTO: the id is a string, the timestamp
        // a JSON array.
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setHeader("__TypeId__", "events.dto.EventNotificationMessageDTO");
        listener.onEventCancelled(new Message("""
                {"eventId":"42","userId":null,"type":"REFUND_REQUEST",
                 "payload":"Event cancelled, trigger refunds for paid bookings","timestamp":[2026,10,17,8,30,15]}"""
                .getBytes(StandardCharsets.UTF_8), properties));

        ArgumentCaptor<RefundRequestedMessage> captor = ArgumentCaptor.forClass(RefundRequestedMessage.class);
        verify(refundService).refundEvent(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(42);
        assertThat(captor.getValue().requestId()).isNull();
    }
}
