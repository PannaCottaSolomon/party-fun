package events.messaging;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import events.dto.EventCancelledMessage;
import events.entity.Booking;
import events.entity.Event;
import events.repository.EventRepository;

/*  Publishes this service's messages to RabbitMQ.

    notify.* messages (notifications exchange) follow the Notification Service's contract (GET /notification-types
    on that service): snake_case fields, amounts in cents, dates as ISO-8601 with an offset, and the recipient as a
    user_id. This service never handles email addresses: the Notification Service looks the user up in the user
    directory it keeps from the Authentication Service's user.* messages. Every message carries a notification_id,
    so a redelivered message never sends the same email twice.

    When called inside a transaction, the message is sent only after that transaction commits, so subscribers
    never hear about a change that was rolled back. Outside a transaction it is sent immediately.
    A broker failure is logged and the message is dropped: the database change has already committed, so the
    request must not fail because of it (best-effort delivery). The exception is event.cancelled: refunds hang on
    it, so it goes through the transactional Outbox and is kept until the broker has confirmed it. */
@Component
public class EventMessaging {

    private static final Logger log = LoggerFactory.getLogger(EventMessaging.class);

    // Routing keys (consumers bind their queues with these or with wildcards such as notify.*)
    private static final String SIGNUP_ROUTING_KEY = "notify.signup";
    private static final String CREATED_ROUTING_KEY = "notify.event_created";
    private static final String UPDATED_ROUTING_KEY = "notify.event_updated";
    private static final String DELETED_ROUTING_KEY = "notify.event_deleted";
    private static final String EVENT_CANCELLED_ROUTING_KEY = "event.cancelled";

    // Event times are stored in UTC; emails show them in Singapore time.
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final String NO_LOCATION = "To be announced";

    // Dependencies
    private final RabbitTemplate rabbitTemplate;  // converts the message to JSON and sends it
    private final Outbox outbox;                  // guaranteed delivery for event.cancelled
    private final EventRepository eventRepository; // looks up the event a booking belongs to
    private final String notificationsExchange;  // from events.rabbitmq.notifications-exchange
    private final String partyEventsExchange;    // from events.rabbitmq.party-events-exchange
    private final Clock clock;      // injectable clock so tests can fix message timestamps

    // Production constructor: timestamps use the UTC system clock.
    @Autowired
    public EventMessaging(RabbitTemplate rabbitTemplate, Outbox outbox, EventRepository eventRepository,
            @Value("${events.rabbitmq.notifications-exchange}") String notificationsExchange,
            @Value("${events.rabbitmq.party-events-exchange}") String partyEventsExchange) {
        this(rabbitTemplate, outbox, eventRepository, notificationsExchange, partyEventsExchange, Clock.systemUTC());
    }

    // Full constructor: lets unit tests pass a fixed Clock
    public EventMessaging(RabbitTemplate rabbitTemplate, Outbox outbox, EventRepository eventRepository,
            String notificationsExchange, String partyEventsExchange, Clock clock) {
        this.rabbitTemplate = rabbitTemplate;
        this.outbox = outbox;
        this.eventRepository = eventRepository;
        this.notificationsExchange = notificationsExchange;
        this.partyEventsExchange = partyEventsExchange;
        this.clock = clock;
    }

    // Booking messages (notifications exchange)

    // Sent once an order is CONFIRMED: payment succeeded, or the event is free. One email per order (a signup's
    // tickets), named after its first booking and with the total paid. An order that is still PENDING (payment
    // declined or not finished) sends nothing: the participant sees that in the app, and the email would otherwise
    // say "You're in!" for places that aren't paid for.
    public void publishSignupConfirmation(List<Booking> order) {
        Booking first = order.get(0);
        Event event = eventRepository.findById(first.getEventId().longValue()).orElse(null);
        if (event == null) {
            log.warn("Booking {} confirmed, but event {} no longer exists: no signup email", first.getId(),
                    first.getEventId());
            return;
        }
        BigDecimal total = order.stream().map(Booking::getTotalAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("notification_id", "signup-booking-" + first.getId());
        message.put("user_id", first.getUserId());
        message.put("event_title", event.getTitle());
        message.put("event_date", isoDate(event.getStartDateTime()));
        message.put("location", location(event));
        message.put("registration_id", first.getId());
        Integer cents = cents(total);
        if (cents != null && cents > 0) {
            message.put("amount", cents);
            message.put("currency", first.getCurrency() != null ? first.getCurrency() : event.getCurrency());
        }
        notification(SIGNUP_ROUTING_KEY, message, "SIGNUP for booking " + first.getId());
    }

    // Event lifecycle messages (notifications exchange)

    // To the organiser: their event is live
    public void publishEventCreated(Event event) {
        if (event.getOrganiserId() == null) {
            return;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("notification_id", "event-created-" + event.getId());
        message.put("user_id", event.getOrganiserId());
        message.put("event_title", event.getTitle());
        message.put("event_date", isoDate(event.getStartDateTime()));
        message.put("location", location(event));
        message.put("event_id", event.getId());
        notification(CREATED_ROUTING_KEY, message, "EVENT_CREATED for event " + event.getId());
    }

    // To every participant (a heads-up) and the organiser (a confirmation). changes: what participants can see that
    // changed, e.g. {"location": "Hall B"}; nothing is sent when it is empty (e.g. only the capacity changed).
    public void publishEventUpdated(Event event, Map<String, String> changes, Collection<Integer> participantIds) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        LocalDateTime version = event.getUpdatedAt() != null ? event.getUpdatedAt() : LocalDateTime.now(clock);
        String prefix = "event-updated-" + event.getId() + "-" + version.toEpochSecond(ZoneOffset.UTC);
        for (Integer userId : distinct(participantIds)) {
            notification(UPDATED_ROUTING_KEY,
                    updated(event, changes, userId, "participant", prefix + "-user-" + userId),
                    "EVENT_UPDATED for event " + event.getId());
        }
        if (event.getOrganiserId() != null) {
            notification(UPDATED_ROUTING_KEY,
                    updated(event, changes, event.getOrganiserId(), "organiser", prefix + "-organiser"),
                    "EVENT_UPDATED for event " + event.getId());
        }
    }

    // Event removal: to every participant who had a place, and the organiser
    public void publishEventDeleted(Event event, Collection<Integer> participantIds) {
        for (Integer userId : distinct(participantIds)) {
            notification(DELETED_ROUTING_KEY,
                    deleted(event, userId, "participant", "event-deleted-" + event.getId() + "-user-" + userId),
                    "EVENT_DELETED for event " + event.getId());
        }
        if (event.getOrganiserId() != null) {
            notification(DELETED_ROUTING_KEY,
                    deleted(event, event.getOrganiserId(), "organiser",
                            "event-deleted-" + event.getId() + "-organiser"),
                    "EVENT_DELETED for event " + event.getId());
        }
    }

    // Used by EventService.cancel(): tells the payment service (party.events / event.cancelled) to cancel the
    // event's bookings on its side and refund every paid payment (saga compensation). Must be called inside the
    // cancelling transaction: the message is stored in the outbox with it, then sent once it commits.
    public void publishEventCancelled(Event event) {
        Integer eventId = event.getId().intValue();
        outbox.enqueue(partyEventsExchange, EVENT_CANCELLED_ROUTING_KEY,
                new EventCancelledMessage(eventId, "cancel-" + eventId, Instant.now(clock).toString(),
                        "Event cancelled by the organiser"));
    }

    // What participants see of an event, as shown in an "event updated" email. EventService takes this before and
    // after an update and passes the differences to publishEventUpdated().
    public static Map<String, String> participantFacingDetails(Event event) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("title", Objects.toString(event.getTitle(), ""));
        details.put("location", location(event));
        details.put("starts", displayDate(event.getStartDateTime()));
        details.put("ends", displayDate(event.getEndDateTime()));
        details.put("ticket price", price(event));
        return details;
    }

    // The entries of after whose value differs from before, e.g. {"location": "Hall B"}
    public static Map<String, String> changedDetails(Map<String, String> before, Map<String, String> after) {
        Map<String, String> changes = new LinkedHashMap<>();
        after.forEach((field, value) -> {
            if (!Objects.equals(before.get(field), value)) {
                changes.put(field, value);
            }
        });
        return changes;
    }

    // Helpers

    private Map<String, Object> updated(Event event, Map<String, String> changes, Integer userId, String audience,
            String notificationId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("notification_id", notificationId);
        message.put("user_id", userId);
        message.put("event_title", event.getTitle());
        message.put("event_id", event.getId());
        message.put("changes", changes);
        message.put("audience", audience);
        return message;
    }

    private Map<String, Object> deleted(Event event, Integer userId, String audience, String notificationId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("notification_id", notificationId);
        message.put("user_id", userId);
        message.put("event_title", event.getTitle());
        message.put("event_id", event.getId());
        message.put("audience", audience);
        return message;
    }

    private static Collection<Integer> distinct(Collection<Integer> userIds) {
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        if (userIds != null) {
            userIds.stream().filter(Objects::nonNull).forEach(unique::add);
        }
        return unique;
    }

    // UTC -> "2026-11-20T19:00+08:00" (the Notification Service formats it for the email)
    private static String isoDate(LocalDateTime utc) {
        return utc == null ? "To be announced" : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(DISPLAY_ZONE)
                .toOffsetDateTime().toString();
    }

    private static String displayDate(LocalDateTime utc) {
        return utc == null ? "To be announced"
                : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(DISPLAY_ZONE).format(DISPLAY_FORMAT);
    }

    private static String location(Event event) {
        return event.getLocation() == null || event.getLocation().isBlank() ? NO_LOCATION : event.getLocation();
    }

    private static String price(Event event) {
        BigDecimal price = event.getTicketPrice();
        if (price == null || price.signum() == 0) {
            return "Free";
        }
        return Objects.toString(event.getCurrency(), "SGD") + " " + price.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // 15.00 -> 1500 (the Notification Service takes amounts in cents, like Stripe)
    private static Integer cents(BigDecimal amount) {
        return amount == null ? null : amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP)
                .intValueExact();
    }

    private void notification(String routingKey, Map<String, Object> message, String description) {
        send(notificationsExchange, routingKey, message, description);
    }

    // Defers the send until the current transaction commits (and drops it on rollback); sends now if there is none.
    // description is only used in the log line if the send fails.
    private void send(String exchange, String routingKey, Object message, String description) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendNow(exchange, routingKey, message, description);
                }
            });
        } else {
            sendNow(exchange, routingKey, message, description);
        }
    }

    private void sendNow(String exchange, String routingKey, Object message, String description) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message);
        } catch (AmqpException exception) {
            log.warn("{} message not sent to {}/{}: {}", description, exchange, routingKey, exception.toString());
        }
    }
}
