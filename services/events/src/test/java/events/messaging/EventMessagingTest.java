package events.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import events.dto.EventCancelledMessage;
import events.entity.Booking;
import events.entity.Event;
import events.repository.EventRepository;

/**
 * Unit tests for {@link EventMessaging}: exchanges, routing keys and bodies of every message (the Notification
 * Service's contract), publishing after commit, that a broker failure never fails the caller, and that
 * event.cancelled goes through the outbox.
 */
@ExtendWith(MockitoExtension.class)
class EventMessagingTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-18T10:00:00Z");

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Outbox outbox;
    @Mock
    private EventRepository eventRepository;

    private EventMessaging messaging;
    private Event event;

    @BeforeEach
    void setUp() {
        messaging = new EventMessaging(rabbitTemplate, outbox, eventRepository, "notifications", "party.events",
                Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
        event = new Event();
        event.setId(3L);
        event.setTitle("CCA Welcome Party");
        event.setLocation("SMU Connexion, Level 3");
        event.setOrganiserId(7);
        event.setStartDateTime(LocalDateTime.of(2026, 11, 14, 12, 0));  // UTC = 8 PM in Singapore
        event.setEndDateTime(LocalDateTime.of(2026, 11, 14, 15, 0));
        event.setTicketPrice(new BigDecimal("15.00"));
        event.setCurrency("SGD");
        event.setUpdatedAt(LocalDateTime.of(2026, 10, 18, 10, 0));
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void eventCreatedTellsTheOrganiserById() {
        messaging.publishEventCreated(event);

        Map<String, Object> message = sent("notify.event_created");
        assertThat(message).containsEntry("notification_id", "event-created-3")
                .containsEntry("user_id", 7)
                .containsEntry("event_title", "CCA Welcome Party")
                .containsEntry("event_date", "2026-11-14T20:00+08:00")
                .containsEntry("location", "SMU Connexion, Level 3")
                .containsEntry("event_id", 3L)
                .doesNotContainKey("email");
    }

    @Test
    void aMissingLocationIsToBeAnnounced() {
        event.setLocation(null);
        messaging.publishEventCreated(event);

        assertThat(sent("notify.event_created")).containsEntry("location", "To be announced");
    }

    @Test
    void theSignupConfirmationCarriesTheBookingAndTheAmountInCents() {
        when(eventRepository.findById(3L)).thenReturn(Optional.of(event));
        Booking paid = booking();
        paid.setTotalAmount(new BigDecimal("15.00"));
        paid.setCurrency("SGD");

        messaging.publishSignupConfirmation(List.of(paid));

        Map<String, Object> message = sent("notify.signup");
        assertThat(message).containsEntry("notification_id", "signup-booking-9")
                .containsEntry("user_id", 42)
                .containsEntry("registration_id", 9)
                .containsEntry("amount", 1500)
                .containsEntry("currency", "SGD")
                .containsEntry("event_date", "2026-11-14T20:00+08:00");
    }

    @Test
    void anOrderOfSeveralTicketsSendsOneSignupConfirmationWithTheTotal() {
        when(eventRepository.findById(3L)).thenReturn(Optional.of(event));
        List<Booking> order = new ArrayList<>();
        for (int id = 9; id <= 11; id++) {
            Booking ticket = booking();
            ticket.setId(id);
            ticket.setTotalAmount(new BigDecimal("15.00"));
            ticket.setCurrency("SGD");
            order.add(ticket);
        }

        messaging.publishSignupConfirmation(order);

        Map<String, Object> message = sent("notify.signup");  // exactly one message for the whole order
        assertThat(message).containsEntry("notification_id", "signup-booking-9")
                .containsEntry("registration_id", 9)
                .containsEntry("amount", 4500);
    }

    @Test
    void aFreeSignupHasNoAmount() {
        when(eventRepository.findById(3L)).thenReturn(Optional.of(event));

        messaging.publishSignupConfirmation(List.of(booking()));

        assertThat(sent("notify.signup")).doesNotContainKeys("amount", "currency");
    }

    @Test
    void noSignupEmailWhenTheEventIsGone() {
        when(eventRepository.findById(3L)).thenReturn(Optional.empty());

        messaging.publishSignupConfirmation(List.of(booking()));

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void anUpdateGoesToEachParticipantOnceAndToTheOrganiser() {
        messaging.publishEventUpdated(event, Map.of("location", "Hall B"), List.of(42, 43, 42));

        List<Map<String, Object>> messages = allSent("notify.event_updated", 3);
        assertThat(messages).extracting(m -> m.get("user_id")).containsExactly(42, 43, 7);
        assertThat(messages).extracting(m -> m.get("audience"))
                .containsExactly("participant", "participant", "organiser");
        assertThat(messages.get(0)).containsEntry("changes", Map.of("location", "Hall B"))
                .containsEntry("notification_id", "event-updated-3-1792317600-user-42");
    }

    @Test
    void anUpdateParticipantsCannotSeeSendsNothing() {
        messaging.publishEventUpdated(event, Map.of(), List.of(42));

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void aDeletionGoesToEachParticipantAndTheOrganiser() {
        messaging.publishEventDeleted(event, List.of(42));

        List<Map<String, Object>> messages = allSent("notify.event_deleted", 2);
        assertThat(messages.get(0)).containsEntry("user_id", 42).containsEntry("audience", "participant")
                .containsEntry("notification_id", "event-deleted-3-user-42");
        assertThat(messages.get(1)).containsEntry("user_id", 7).containsEntry("audience", "organiser");
    }

    @Test
    void onlyWhatParticipantsSeeCountsAsAChange() {
        Map<String, String> before = EventMessaging.participantFacingDetails(event);
        event.setCapacity(1);
        event.setTicketPrice(new BigDecimal("20"));
        event.setStartDateTime(LocalDateTime.of(2026, 11, 14, 13, 30));

        Map<String, String> changes =
                EventMessaging.changedDetails(before, EventMessaging.participantFacingDetails(event));

        assertThat(changes).containsExactly(Map.entry("starts", "Sat, 14 Nov 2026, 9:30 PM"),
                Map.entry("ticket price", "SGD 20.00"));
    }

    @Test
    void eventCancelledUsesThePaymentServiceContractAndGoesThroughTheOutbox() {
        messaging.publishEventCancelled(event);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(eq("party.events"), eq("event.cancelled"), body.capture());
        assertThat(body.getValue()).isEqualTo(new EventCancelledMessage(3, "cancel-3", "2026-10-18T10:00:00Z",
                "Event cancelled by the organiser"));
        verifyNoInteractions(rabbitTemplate);  // never sent best effort
    }

    @Test
    void insideATransactionTheMessageIsSentOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        messaging.publishEventCreated(event);
        verifyNoInteractions(rabbitTemplate);  // a rollback would never reach afterCommit, so nothing is sent

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        sent("notify.event_created");
    }

    @Test
    void aBrokerFailureIsLoggedNotThrown() {
        doThrow(new AmqpConnectException(new ConnectException("refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> messaging.publishEventDeleted(event, List.of(42))).doesNotThrowAnyException();
    }

    private Booking booking() {
        Booking booking = new Booking();
        booking.setId(9);
        booking.setEventId(3);
        booking.setUserId(42);
        return booking;
    }

    private Map<String, Object> sent(String routingKey) {
        return allSent(routingKey, 1).get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allSent(String routingKey, int count) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(count)).convertAndSend(eq("notifications"), eq(routingKey), body.capture());
        return body.getAllValues().stream().map(value -> (Map<String, Object>) value).toList();
    }
}
