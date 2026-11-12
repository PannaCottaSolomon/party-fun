package events.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import events.entity.Booking;
import events.entity.BookingStatus;
import events.entity.Event;
import events.exception.DuplicateBookingException;
import events.exception.EventFullException;
import events.exception.EventNotFoundException;
import events.exception.EventNotOpenException;
import events.exception.InvalidRequestException;
import events.repository.BookingRepository;
import events.repository.EventRepository;

/**
 * Unit tests for {@link BookingReservationService#reserve}: the rules checked under the event row lock and the
 * PENDING booking it creates. Row locking itself is covered by the integration tests.
 */
@ExtendWith(MockitoExtension.class)
class BookingReservationServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-18T10:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);

    @Mock
    private EventRepository eventRepository;
    @Mock
    private BookingRepository bookingRepository;

    private BookingReservationService service;
    private Event event;

    @BeforeEach
    void setUp() {
        service = new BookingReservationService(eventRepository, bookingRepository,
                Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
        event = new Event();
        event.setId(1L);
        event.setTitle("Party");
        event.setStatus("ACTIVE");
        event.setCapacity(2);
        event.setTicketPrice(new BigDecimal("15.00"));
        event.setCurrency("SGD");
    }

    @Test
    void reservesAPlaceAndCreatesAPendingBookingWithAFifteenMinuteWindow() {
        lockReturns(event);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Booking booking = service.reserve(1, 42, "pm_card_visa", 1).get(0);

        assertThat(event.getCapacity()).isEqualTo(1);
        assertThat(booking.getEventId()).isEqualTo(1);
        assertThat(booking.getUserId()).isEqualTo(42);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("15.00");
        assertThat(booking.getCurrency()).isEqualTo("SGD");
        assertThat(booking.getCreatedAt()).isEqualTo(NOW);
        assertThat(booking.getExpiresAt()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    void aUserIdIsRequired() {
        assertThatThrownBy(() -> service.reserve(1, null, "pm_card_visa", 1)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(eventRepository);
    }

    @Test
    void anUnknownEventIsNotFound() {
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(1, 42, "pm_card_visa", 1)).isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void anEventThatIsNotActiveIsNotOpen() {
        event.setStatus("CLOSED");
        lockReturns(event);

        assertThatThrownBy(() -> service.reserve(1, 42, "pm_card_visa", 1)).isInstanceOf(EventNotOpenException.class);
    }

    @Test
    void aPaidEventNeedsAPaymentMethodAndNoPlaceIsTaken() {
        lockReturns(event);

        assertThatThrownBy(() -> service.reserve(1, 42, " ", 1)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("paymentMethodId");
        assertThat(event.getCapacity()).isEqualTo(2);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aFreeEventNeedsNoPaymentMethod() {
        event.setTicketPrice(null);
        lockReturns(event);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.reserve(1, 42, null, 1).get(0).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void aUserWithAnActiveBookingCannotBookAgain() {
        lockReturns(event);
        when(bookingRepository.existsByEventIdAndUserIdAndStatusIn(eq(1), eq(42), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.reserve(1, 42, "pm_card_visa", 1)).isInstanceOf(DuplicateBookingException.class);
        assertThat(event.getCapacity()).isEqualTo(2);
    }

    @Test
    void aFullEventRefusesTheSignup() {
        event.setCapacity(0);
        lockReturns(event);

        assertThatThrownBy(() -> service.reserve(1, 42, "pm_card_visa", 1)).isInstanceOf(EventFullException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aLapsedPendingBookingOfTheSameUserIsExpiredAndItsPlaceReused() {
        event.setCapacity(0);
        lockReturns(event);
        Booking lapsed = new Booking();
        lapsed.setStatus(BookingStatus.PENDING);
        when(bookingRepository.findByEventIdAndUserIdAndStatusAndExpiresAtLessThanEqual(1, 42, BookingStatus.PENDING, NOW))
                .thenReturn(List.of(lapsed));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Booking booking = service.reserve(1, 42, "pm_card_visa", 1).get(0);

        assertThat(lapsed.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(event.getCapacity()).isZero();  // +1 from the lapsed booking, -1 for the new one
    }

    // ---------- several tickets in one signup ----------

    @Test
    void aSignupForSeveralTicketsTakesAPlaceAndCreatesAPendingBookingForEach() {
        event.setCapacity(5);
        lockReturns(event);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Booking> bookings = service.reserve(1, 42, "pm_card_visa", 3);

        assertThat(bookings).hasSize(3).doesNotHaveDuplicates().allSatisfy(booking -> {
            assertThat(booking.getUserId()).isEqualTo(42);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThat(booking.getTotalAmount()).isEqualByComparingTo("15.00");
            assertThat(booking.getExpiresAt()).isEqualTo(NOW.plusMinutes(15));
        });
        // One order: the tickets are paid by one charge and confirmed together
        assertThat(bookings).extracting(Booking::getOrderId).doesNotContainNull().containsOnly(bookings.get(0).getOrderId());
        assertThat(event.getCapacity()).isEqualTo(2);
        verify(bookingRepository, times(3)).save(any(Booking.class));
    }

    @Test
    void eachSignupIsAnOrderOfItsOwn() {
        event.setCapacity(5);
        lockReturns(event);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String first = service.reserve(1, 42, "pm_card_visa", 1).get(0).getOrderId();
        String second = service.reserve(1, 43, "pm_card_visa", 1).get(0).getOrderId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aNullQuantityMeansOneTicket() {
        lockReturns(event);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.reserve(1, 42, "pm_card_visa", null)).hasSize(1);
        assertThat(event.getCapacity()).isEqualTo(1);
    }

    @Test
    void moreTicketsThanPlacesLeftReservesNone() {
        lockReturns(event);  // capacity 2

        assertThatThrownBy(() -> service.reserve(1, 42, "pm_card_visa", 3)).isInstanceOf(EventFullException.class)
                .hasMessage("Event 1 has only 2 place(s) left, 3 requested");
        assertThat(event.getCapacity()).isEqualTo(2);
        verify(bookingRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1, BookingReservationService.MAX_TICKETS_PER_SIGNUP + 1 })
    void aQuantityOutsideTheAllowedRangeIsRejectedBeforeLocking(int quantity) {
        assertThatThrownBy(() -> service.reserve(1, 42, "pm_card_visa", quantity))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("quantity");
        verifyNoInteractions(eventRepository, bookingRepository);
    }

    private void lockReturns(Event locked) {
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(locked));
    }
}
