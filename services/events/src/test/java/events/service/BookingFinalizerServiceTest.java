package events.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import events.entity.Booking;
import events.entity.BookingStatus;
import events.repository.BookingRepository;

/**
 * Unit tests for {@link BookingFinalizerService}: recording payment outcomes on the bookings of an order.
 */
@ExtendWith(MockitoExtension.class)
class BookingFinalizerServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-18T10:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);

    @Mock
    private BookingRepository bookingRepository;

    private BookingFinalizerService service;
    private Booking booking;

    @BeforeEach
    void setUp() {
        service = new BookingFinalizerService(bookingRepository, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
        booking = pending(1);
    }

    private static Booking pending(int id) {
        Booking pending = new Booking();
        pending.setId(id);
        pending.setEventId(3);
        pending.setStatus(BookingStatus.PENDING);
        return pending;
    }

    @Test
    void confirmMarksAPendingBookingConfirmed() {
        when(bookingRepository.confirmIfPending(List.of(1), "5", NOW)).thenReturn(1);

        assertThat(service.confirm(List.of(booking), "5")).isTrue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getConfirmedAt()).isEqualTo(NOW);
        assertThat(booking.getPaymentReference()).isEqualTo("5");
    }

    @Test
    void confirmMarksEveryBookingOfAnOrderConfirmed() {
        Booking second = pending(2);
        when(bookingRepository.confirmIfPending(List.of(1, 2), "5", NOW)).thenReturn(2);

        assertThat(service.confirm(List.of(booking, second), "5")).isTrue();
        assertThat(List.of(booking, second)).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(b.getPaymentReference()).isEqualTo("5");
        });
    }

    @Test
    void confirmChangesNothingWhenTheBookingIsNoLongerPending() {
        when(bookingRepository.confirmIfPending(List.of(1), "5", NOW)).thenReturn(0);

        assertThat(service.confirm(List.of(booking), "5")).isFalse();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getPaymentReference()).isNull();
    }

    @Test
    void confirmIsAllOrNothingWhenOnlyPartOfAnOrderIsStillPending() {
        Booking second = pending(2);
        when(bookingRepository.confirmIfPending(List.of(1, 2), "5", NOW)).thenReturn(1);

        assertThat(service.confirm(List.of(booking, second), "5")).isFalse();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void recordPaymentFailureKeepsTheBookingPendingAndTruncatesTheReasonToTheColumnSize() {
        String longReason = "x".repeat(300);
        String stored = "x".repeat(255);
        when(bookingRepository.recordFailureIfPending(1, stored, NOW)).thenReturn(1);

        service.recordPaymentFailure(List.of(booking), longReason);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getFailedAt()).isEqualTo(NOW);
        assertThat(booking.getFailureReason()).isEqualTo(stored);
    }

    @Test
    void recordPaymentFailureRecordsItOnEveryBookingOfAnOrder() {
        Booking second = pending(2);
        when(bookingRepository.recordFailureIfPending(1, "declined", NOW)).thenReturn(1);
        when(bookingRepository.recordFailureIfPending(2, "declined", NOW)).thenReturn(1);

        service.recordPaymentFailure(List.of(booking, second), "declined");

        assertThat(booking.getFailureReason()).isEqualTo("declined");
        assertThat(second.getFailureReason()).isEqualTo("declined");
    }

    @Test
    void recordPaymentFailureChangesNothingWhenTheBookingIsNoLongerPending() {
        when(bookingRepository.recordFailureIfPending(1, "declined", NOW)).thenReturn(0);

        service.recordPaymentFailure(List.of(booking), "declined");

        assertThat(booking.getFailedAt()).isNull();
        assertThat(booking.getFailureReason()).isNull();
    }

    @Test
    void linkPaymentBookingStoresTheIdOnEveryBookingOfTheOrder() {
        Booking second = pending(2);

        service.linkPaymentBooking(List.of(booking, second), 7);

        verify(bookingRepository).linkPaymentBooking(List.of(1, 2), 7);
        assertThat(booking.getPaymentBookingId()).isEqualTo(7);
        assertThat(second.getPaymentBookingId()).isEqualTo(7);
    }

    @Test
    void recordRefundOwedStoresThePaymentToRefund() {
        when(bookingRepository.recordRefundOwed(1, "5", NOW)).thenReturn(1);

        service.recordRefundOwed(booking, 5);

        assertThat(booking.getPaymentReference()).isEqualTo("5");
        assertThat(booking.getRefundRequiredAt()).isEqualTo(NOW);
    }

    @Test
    void recordRefundOwedKeepsAnEarlierOwedRefund() {
        when(bookingRepository.recordRefundOwed(1, "5", NOW)).thenReturn(0);

        service.recordRefundOwed(booking, 5);

        assertThat(booking.getRefundRequiredAt()).isNull();
    }

    @Test
    void recordRefundSettledStoresHowItWasSettled() {
        when(bookingRepository.recordRefundSettled(1, "REFUNDED", NOW)).thenReturn(1);

        service.recordRefundSettled(booking, "REFUNDED");

        assertThat(booking.getRefundedAt()).isEqualTo(NOW);
        assertThat(booking.getRefundReference()).isEqualTo("REFUNDED");
    }

    @Test
    void recordRefundSettledChangesNothingWhenItWasAlreadySettled() {
        when(bookingRepository.recordRefundSettled(1, "REFUNDED", NOW)).thenReturn(0);

        service.recordRefundSettled(booking, "REFUNDED");

        assertThat(booking.getRefundedAt()).isNull();
    }
}
