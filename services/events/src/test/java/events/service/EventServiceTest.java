package events.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import events.client.PaymentUnavailableException;
import events.dto.EventDto;
import events.entity.Booking;
import events.entity.BookingStatus;
import events.entity.Event;
import events.exception.BookingNotFoundException;
import events.exception.BookingNotPayableException;
import events.exception.EventNotFoundException;
import events.exception.InvalidRequestException;
import events.messaging.EventMessaging;
import events.repository.BookingRepository;
import events.repository.EventRepository;

/**
 * Unit tests for {@link EventService}: event validation and lifecycle, auto-cancelling events that missed their
 * minimum signups, and the signup / payment-retry orchestration.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-18T10:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);

    @Mock
    private EventRepository eventRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingReservationService reservationService;
    @Mock
    private BookingPaymentService paymentService;
    @Mock
    private EventMessaging eventMessaging;

    private EventService service;

    @BeforeEach
    void setUp() {
        service = new EventService(eventRepository, bookingRepository, reservationService, paymentService,
                eventMessaging, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
    }

    // ---------- create / update ----------

    @Test
    void createDefaultsStatusAndCurrencySetsTimestampsAndPublishes() {
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        EventDto created = service.create(request(dto -> { }));

        assertThat(created.getId()).isEqualTo(1);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getCurrency()).isEqualTo("SGD");
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(created.getUpdatedAt()).isEqualTo(NOW);
        verify(eventMessaging).publishEventCreated(any(Event.class));
    }

    @Test
    void createNormalisesALowerCaseCurrency() {
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(request(dto -> dto.setCurrency(" sgd "))).getCurrency()).isEqualTo("SGD");
    }

    @Test
    void createAcceptsAFreeEventAndTrailingZeros() {
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(request(dto -> dto.setTicketPrice(null))).getTicketPrice()).isNull();
        assertThat(service.create(request(dto -> dto.setTicketPrice(new BigDecimal("15.000")))).getTicketPrice())
                .isEqualByComparingTo("15");
    }

    @Test
    void createStartsMaxCapacityAtTheCapacityUnlessOneIsGiven() {
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(request(dto -> { })).getMaxCapacity()).isEqualTo(10);
        assertThat(service.create(request(dto -> dto.setMaxCapacity(12))).getMaxCapacity()).isEqualTo(12);
    }

    @Test
    void createKeepsTheMinimumSignupsAndTheirDeadline() {
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EventDto created = service.create(request(dto -> {
            dto.setMinSignUps(3);
            dto.setExpiresAt(NOW.plusDays(7));
        }));

        assertThat(created.getMinSignUps()).isEqualTo(3);
        assertThat(created.getExpiresAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    void createRejectsAnotherCurrency() {
        assertThatThrownBy(() -> service.create(request(dto -> dto.setCurrency("USD"))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("SGD");
        verifyNoInteractions(eventRepository, eventMessaging);
    }

    @ParameterizedTest
    @ValueSource(strings = { "-0.01", "15.005", "100000000.00" })
    void createRejectsATicketPriceThePaymentServiceCannotCharge(String price) {
        assertThatThrownBy(() -> service.create(request(dto -> dto.setTicketPrice(new BigDecimal(price)))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Ticket price");
    }

    @Test
    void createRejectsAMissingTitleBadTimesAndNoCapacity() {
        assertThatThrownBy(() -> service.create(request(dto -> dto.setTitle(" "))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> service.create(request(dto -> dto.setEndDateTime(dto.getStartDateTime()))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("end time");
        assertThatThrownBy(() -> service.create(request(dto -> dto.setCapacity(0))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("capacity");
    }

    @Test
    void updateAppliesOnlyTheFieldsSentAndAllowsASoldOutCapacity() {
        Event event = existingEvent();
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        EventDto patch = new EventDto();
        patch.setLocation("Hall B");
        patch.setCapacity(0);
        patch.setMaxCapacity(20);
        patch.setMinSignUps(5);
        patch.setExpiresAt(NOW.plusDays(1));

        EventDto updated = service.update(1, patch);

        assertThat(updated.getLocation()).isEqualTo("Hall B");
        assertThat(updated.getCapacity()).isZero();
        assertThat(updated.getMaxCapacity()).isEqualTo(20);
        assertThat(updated.getMinSignUps()).isEqualTo(5);
        assertThat(updated.getExpiresAt()).isEqualTo(NOW.plusDays(1));
        assertThat(updated.getTitle()).isEqualTo("Party");
        assertThat(updated.getUpdatedAt()).isEqualTo(NOW);
        // Only what participants can see goes in the email; nobody holds a place yet, so only the organiser
        verify(eventMessaging).publishEventUpdated(event, Map.of("location", "Hall B"), List.of());
    }

    @Test
    void updateRejectsAnotherCurrency() {
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(existingEvent()));
        EventDto patch = new EventDto();
        patch.setCurrency("EUR");

        assertThatThrownBy(() -> service.update(1, patch)).isInstanceOf(InvalidRequestException.class);
        verify(eventMessaging, never()).publishEventUpdated(any(), any(), any());
    }

    @Test
    void anUnknownEventIsNotFound() {
        when(eventRepository.findById(9L)).thenReturn(Optional.empty());
        when(eventRepository.findByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9)).isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> service.update(9, new EventDto())).isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> service.delete(9)).isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(9)).isInstanceOf(EventNotFoundException.class);
        verifyNoInteractions(bookingRepository, eventMessaging);
    }

    // ---------- delete / cancel ----------

    @Test
    void deleteCancelsTheActiveBookingsRemovesTheEventAndPublishesDeleted() {
        Event event = existingEvent();
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));

        service.delete(1);

        InOrder order = inOrder(bookingRepository, eventRepository, eventMessaging);
        order.verify(bookingRepository).cancelActiveBookingsForEvent(1);
        order.verify(eventRepository).delete(event);
        order.verify(eventMessaging).publishEventDeleted(eq(event), any());
        verify(eventMessaging, never()).publishEventCancelled(any());
    }

    @Test
    void cancelDeletesThenAsksThePaymentServiceForRefunds() {
        Event event = existingEvent();
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));

        service.cancel(1);

        InOrder order = inOrder(bookingRepository, eventRepository, eventMessaging);
        order.verify(bookingRepository).cancelActiveBookingsForEvent(1);
        order.verify(eventRepository).delete(event);
        order.verify(eventMessaging).publishEventDeleted(eq(event), any());
        order.verify(eventMessaging).publishEventCancelled(event);
        verify(eventRepository).findByIdForUpdate(1L);  // locked once: the delete reuses the locked event
    }

    @Test
    void everyoneWithAPlaceIsNotifiedWhenTheEventIsDeleted() {
        Event event = existingEvent();
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        Booking paid = booking(BookingStatus.CONFIRMED);
        Booking held = booking(BookingStatus.PENDING);
        held.setUserId(43);
        when(bookingRepository.findByEventIdAndStatusIn(eq(1), any())).thenReturn(List.of(paid, held));

        service.delete(1);

        InOrder order = inOrder(bookingRepository, eventMessaging);
        order.verify(bookingRepository).findByEventIdAndStatusIn(eq(1), any());  // read before they are cancelled
        order.verify(bookingRepository).cancelActiveBookingsForEvent(1);
        order.verify(eventMessaging).publishEventDeleted(event, List.of(42, 43));
    }

    @Test
    void participantsHearAboutAnUpdateWithJustWhatChanged() {
        Event event = existingEvent();
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        when(bookingRepository.findByEventIdAndStatusIn(eq(1), any()))
                .thenReturn(List.of(booking(BookingStatus.CONFIRMED)));
        EventDto patch = new EventDto();
        patch.setTitle("Party");          // unchanged
        patch.setTicketPrice(new BigDecimal("20.00"));
        patch.setCapacity(3);             // not something participants see

        service.update(1, patch);

        verify(eventMessaging).publishEventUpdated(event, Map.of("ticket price", "SGD 20.00"), List.of(42));
    }

    // ---------- expiry (auto-cancel) ----------

    @Test
    void anExpiredEventThatMissedItsMinimumSignupsIsCancelledLikeAnOrganiserWould() {
        Event event = expiredEvent(1L, 2, 3);
        when(eventRepository.findByExpiresAtLessThanEqualAndStatus(NOW, "ACTIVE")).thenReturn(List.of(event));
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));

        service.processExpiredEvents();

        InOrder order = inOrder(bookingRepository, eventRepository, eventMessaging);
        order.verify(bookingRepository).cancelActiveBookingsForEvent(1);
        order.verify(eventRepository).delete(event);
        order.verify(eventMessaging).publishEventDeleted(eq(event), any());
        order.verify(eventMessaging).publishEventCancelled(event);  // the payment service refunds paid bookings
    }

    @Test
    void onlyTheExpiredEventsBelowTheirMinimumAreCancelled() {
        Event missed = expiredEvent(1L, 2, 3);
        Event reachedExactly = expiredEvent(2L, 3, 3);
        Event noMinimum = expiredEvent(3L, 0, null);
        when(eventRepository.findByExpiresAtLessThanEqualAndStatus(NOW, "ACTIVE"))
                .thenReturn(List.of(missed, reachedExactly, noMinimum));
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(missed));

        service.processExpiredEvents();

        verify(eventRepository).delete(missed);
        verify(eventMessaging).publishEventDeleted(eq(missed), any());
        verify(eventMessaging).publishEventCancelled(missed);
        verifyNoMoreInteractions(eventMessaging);
        assertThat(reachedExactly.getStatus()).isEqualTo("ACTIVE");
        assertThat(noMinimum.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void withNoExpiredEventsNothingIsCancelled() {
        when(eventRepository.findByExpiresAtLessThanEqualAndStatus(NOW, "ACTIVE")).thenReturn(List.of());

        service.processExpiredEvents();

        verifyNoInteractions(bookingRepository, eventMessaging);
    }

    // ---------- signup ----------

    @Test
    void aPaidSignupPublishesTheConfirmation() {
        Booking booking = booking(BookingStatus.PENDING);
        when(reservationService.reserve(1, 42, "pm_card_visa", 1)).thenReturn(List.of(booking));
        when(paymentService.pay(List.of(booking), "pm_card_visa")).thenReturn(true);

        assertThat(service.signUp(1, 42, "pm_card_visa", 1)).containsExactly(booking);
        verify(eventMessaging).publishSignupConfirmation(List.of(booking));
        verifyNoMoreInteractions(eventMessaging);
    }

    @Test
    void anUnpaidSignupSendsNoEmail() {
        Booking booking = booking(BookingStatus.PENDING);
        when(reservationService.reserve(1, 42, "pm_card_chargeDeclined", 1)).thenReturn(List.of(booking));
        when(paymentService.pay(List.of(booking), "pm_card_chargeDeclined")).thenReturn(false);

        service.signUp(1, 42, "pm_card_chargeDeclined", 1);

        verifyNoInteractions(eventMessaging);  // "You're in!" only once it is paid
    }

    @Test
    void aPaymentServiceOutageDoesNotFailTheSignup() {
        Booking booking = booking(BookingStatus.PENDING);
        when(reservationService.reserve(1, 42, "pm_card_visa", 1)).thenReturn(List.of(booking));
        when(paymentService.pay(List.of(booking), "pm_card_visa")).thenThrow(new PaymentUnavailableException("down"));

        assertThat(service.signUp(1, 42, "pm_card_visa", 1)).singleElement()
                .extracting(Booking::getStatus).isEqualTo(BookingStatus.PENDING);
        verifyNoInteractions(eventMessaging);
    }

    @Test
    void aSignupCompensatedWithARefundPublishesNothing() {
        Booking booking = booking(BookingStatus.PENDING);
        when(reservationService.reserve(1, 42, "pm_card_visa", 1)).thenReturn(List.of(booking));
        when(paymentService.pay(List.of(booking), "pm_card_visa")).thenAnswer(invocation -> {
            booking.setStatus(BookingStatus.CANCELLED);
            return false;
        });

        service.signUp(1, 42, "pm_card_visa", 1);

        verifyNoInteractions(eventMessaging);
    }

    @Test
    void aMultiTicketSignupIsChargedOnceAndConfirmedWithOneEmail() {
        Booking first = booking(BookingStatus.PENDING);
        Booking second = booking(BookingStatus.PENDING);
        second.setId(6);
        List<Booking> order = List.of(first, second);
        when(reservationService.reserve(1, 42, "pm_card_visa", 2)).thenReturn(order);
        when(paymentService.pay(order, "pm_card_visa")).thenReturn(true);

        assertThat(service.signUp(1, 42, "pm_card_visa", 2)).containsExactly(first, second);
        verify(paymentService).pay(order, "pm_card_visa");  // one charge for both tickets
        verify(eventMessaging).publishSignupConfirmation(order);
        verifyNoMoreInteractions(eventMessaging);
    }

    @Test
    void aPaymentServiceOutageLeavesEveryTicketOfTheSignupPending() {
        Booking first = booking(BookingStatus.PENDING);
        Booking second = booking(BookingStatus.PENDING);
        second.setId(6);
        List<Booking> order = List.of(first, second);
        when(reservationService.reserve(1, 42, "pm_card_visa", 2)).thenReturn(order);
        when(paymentService.pay(order, "pm_card_visa")).thenThrow(new PaymentUnavailableException("down"));

        assertThat(service.signUp(1, 42, "pm_card_visa", 2)).extracting(Booking::getStatus)
                .containsOnly(BookingStatus.PENDING);
        verifyNoInteractions(eventMessaging);
    }

    // ---------- a user's bookings ----------

    @Test
    void listsAUsersBookingsInEveryStatus() {
        List<Booking> bookings = List.of(booking(BookingStatus.CONFIRMED), booking(BookingStatus.CANCELLED));
        when(bookingRepository.findByUserIdOrderByCreatedAtDescIdDesc(42)).thenReturn(bookings);

        assertThat(service.listBookingsForUser(42)).isSameAs(bookings);
    }

    @Test
    void listingBookingsNeedsAUserId() {
        assertThatThrownBy(() -> service.listBookingsForUser(null)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(bookingRepository);
    }

    // ---------- payment retry ----------

    @Test
    void aPaymentRetryNeedsAPaymentMethod() {
        assertThatThrownBy(() -> service.payForBooking(1, 5, null)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(bookingRepository, paymentService);
    }

    @Test
    void aPaymentRetryForAnUnknownBookingOrAnotherEventsBookingIsNotFound() {
        when(bookingRepository.findById(5)).thenReturn(Optional.empty(), Optional.of(booking(BookingStatus.PENDING)));

        assertThatThrownBy(() -> service.payForBooking(1, 5, "pm_card_visa")).isInstanceOf(BookingNotFoundException.class);
        assertThatThrownBy(() -> service.payForBooking(2, 5, "pm_card_visa")).isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void anExpiredOrCancelledBookingCannotBePaid() {
        when(bookingRepository.findById(5)).thenReturn(Optional.of(booking(BookingStatus.EXPIRED)),
                Optional.of(booking(BookingStatus.CANCELLED)));

        assertThatThrownBy(() -> service.payForBooking(1, 5, "pm_card_visa"))
                .isInstanceOf(BookingNotPayableException.class).hasMessageContaining("EXPIRED");
        assertThatThrownBy(() -> service.payForBooking(1, 5, "pm_card_visa"))
                .isInstanceOf(BookingNotPayableException.class).hasMessageContaining("CANCELLED");
        verifyNoInteractions(paymentService);
    }

    @Test
    void aBookingAlreadyConfirmedIsReturnedWithoutCharging() {
        Booking confirmed = booking(BookingStatus.CONFIRMED);
        when(bookingRepository.findById(5)).thenReturn(Optional.of(confirmed));

        assertThat(service.payForBooking(1, 5, "pm_card_visa")).isSameAs(confirmed);
        verifyNoInteractions(paymentService, eventMessaging);
    }

    @Test
    void aBookingCannotBePaidOnceItsWindowHasClosed() {
        Booking booking = booking(BookingStatus.PENDING);
        booking.setExpiresAt(NOW);
        when(bookingRepository.findById(5)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.payForBooking(1, 5, "pm_card_visa"))
                .isInstanceOf(BookingNotPayableException.class).hasMessageContaining("payment window");
        verifyNoInteractions(paymentService);
    }

    @Test
    void aSuccessfulPaymentRetryPublishesTheConfirmation() {
        Booking booking = booking(BookingStatus.PENDING);
        when(bookingRepository.findById(5)).thenReturn(Optional.of(booking));
        when(paymentService.unpaidOrderOf(booking)).thenReturn(List.of(booking));
        when(paymentService.pay(List.of(booking), "pm_card_visa")).thenReturn(true);

        assertThat(service.payForBooking(1, 5, "pm_card_visa")).isSameAs(booking);
        verify(eventMessaging).publishSignupConfirmation(List.of(booking));
    }

    @Test
    void aPaymentRetryPaysEveryUnpaidTicketOfTheBookingsOrderAtOnce() {
        Booking booking = booking(BookingStatus.PENDING);
        Booking sibling = booking(BookingStatus.PENDING);
        sibling.setId(6);
        List<Booking> order = List.of(booking, sibling);
        when(bookingRepository.findById(5)).thenReturn(Optional.of(booking));
        when(paymentService.unpaidOrderOf(booking)).thenReturn(order);
        when(paymentService.pay(order, "pm_card_visa")).thenReturn(true);

        assertThat(service.payForBooking(1, 5, "pm_card_visa")).isSameAs(booking);
        verify(paymentService).pay(order, "pm_card_visa");
        verify(eventMessaging).publishSignupConfirmation(order);
    }

    @Test
    void aPaymentServiceOutageFailsThePaymentRetry() {
        Booking booking = booking(BookingStatus.PENDING);
        when(bookingRepository.findById(5)).thenReturn(Optional.of(booking));
        when(paymentService.unpaidOrderOf(booking)).thenReturn(List.of(booking));
        when(paymentService.pay(List.of(booking), "pm_card_visa")).thenThrow(new PaymentUnavailableException("down"));

        assertThatThrownBy(() -> service.payForBooking(1, 5, "pm_card_visa"))
                .isInstanceOf(PaymentUnavailableException.class);
        verifyNoInteractions(eventMessaging);
    }

    // ---------- helpers ----------

    private static EventDto request(Consumer<EventDto> customiser) {
        EventDto dto = new EventDto();
        dto.setTitle("Party");
        dto.setStartDateTime(LocalDateTime.of(2026, 11, 20, 19, 0));
        dto.setEndDateTime(LocalDateTime.of(2026, 11, 20, 23, 0));
        dto.setCapacity(10);
        dto.setTicketPrice(new BigDecimal("15.00"));
        customiser.accept(dto);
        return dto;
    }

    private static Event existingEvent() {
        Event event = request(dto -> { }).toEntity();
        event.setId(1L);
        event.setStatus("ACTIVE");
        event.setCurrency("SGD");
        return event;
    }

    // An ACTIVE event of 10 places whose signup deadline passed a minute ago
    private static Event expiredEvent(long id, int signUps, Integer minSignUps) {
        Event event = existingEvent();
        event.setId(id);
        event.setMaxCapacity(10);
        event.setCapacity(10 - signUps);
        event.setMinSignUps(minSignUps);
        event.setExpiresAt(NOW.minusMinutes(1));
        return event;
    }

    private static Booking booking(BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(5);
        booking.setEventId(1);
        booking.setUserId(42);
        booking.setStatus(status);
        booking.setExpiresAt(NOW.plusMinutes(10));
        return booking;
    }
}
