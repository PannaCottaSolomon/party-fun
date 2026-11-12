package payment.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import payment.service.RefundService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code event.cancelled} (published by the Event Service on {@code party.events} after it cancels an
 * event) from {@code payments.refunds.requested}: one message per cancelled event. Payment then cancels the event's
 * open bookings and refunds every paid payment individually itself ({@link RefundService#refundEvent}).
 *
 * <ul>
 *   <li>Each refund is handled on its own, so a failing refund never fails this message: it becomes REFUND_FAILED
 *       and Payment publishes it individually to the refund DLQ.</li>
 *   <li>The message is acknowledged only after every payment was handled. If the service stops mid-way, RabbitMQ
 *       redelivers it and the refunds resume idempotently.</li>
 *   <li>A malformed message can never succeed: it is logged and dropped (acked) straight away, NOT sent
 *       to the refund DLQ, which is only for failed refunds.</li>
 *   <li>Any other error (e.g. the database briefly down) is retried and then requeued, never dropped (see
 *       {@code RabbitConfig#eventCancelledContainerFactory}).</li>
 * </ul>
 */
@Component
public class EventCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(EventCancelledListener.class);

    private final RefundService refundService;
    private final JsonMapper jsonMapper;

    public EventCancelledListener(RefundService refundService, JsonMapper jsonMapper) {
        this.refundService = refundService;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(queues = "${payment.messaging.refund-requested-queue}",
            containerFactory = "eventCancelledContainerFactory")
    public void onEventCancelled(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        RefundRequestedMessage request;
        try {
            request = jsonMapper.readValue(message.getBody(), RefundRequestedMessage.class);
        } catch (JacksonException e) {
            log.error("Dropping {} message {}: body is not valid JSON",
                    routingKey, message.getMessageProperties().getMessageId());
            return; // acked and discarded: retrying can never fix it
        }
        if (request == null || request.eventId() == null || request.eventId() <= 0) {
            log.error("Dropping {} message {}: no valid eventId",
                    routingKey, message.getMessageProperties().getMessageId());
            return;
        }
        log.info("Received {} for event {} (requestId={})", routingKey, request.eventId(), request.requestId());
        refundService.refundEvent(request);
    }
}
