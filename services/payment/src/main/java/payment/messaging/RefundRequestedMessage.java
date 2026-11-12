package payment.messaging;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Incoming {@code event.cancelled} message, published by the Event Service after an event is cancelled. Only
 * {@code eventId} is required; Payment finds the affected payments through its own {@code booking.event_id}
 * column. Everything else is ignored.
 *
 * <p>Accepts the Event Service's current message shape ({@code EventNotificationMessageDTO}), where the id is a
 * string and the timestamp is a JSON array:
 * <pre>{"eventId":"42","userId":null,"type":"REFUND_REQUEST","payload":"Event cancelled, ...",
 *  "timestamp":[2026,10,17,8,30,15]}</pre>
 * as well as a plain {@code {"eventId": 42, "requestId": "cancel-42"}}. camelCase and snake_case are accepted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundRequestedMessage(
        @JsonAlias("event_id") Integer eventId,
        @JsonAlias("request_id") String requestId) {
}
