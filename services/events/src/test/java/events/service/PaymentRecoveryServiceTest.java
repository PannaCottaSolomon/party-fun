package events.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import events.client.PaymentUnavailableException;
import events.entity.Booking;
import events.messaging.EventMessaging;
import events.repository.BookingRepository;

/**
 * Unit tests for {@link PaymentRecoveryService}: owed refunds are retried first, then PENDING bookings whose payment
 * answer was lost are confirmed if the card was charged, and only those are announced.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock
    private BookingRefundService refundService;
    @Mock
    private BookingPaymentService paymentService;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private EventMessaging eventMessaging;

    private PaymentRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new PaymentRecoveryService(refundService, paymentService, bookingRepository, eventMessaging,
                Duration.ofMinutes(1), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void owedRefundsAreRetriedBeforeLostPaymentsAreLookedFor() {
        when(bookingRepository.findUnconfirmedPayments(NOW_UTC.minusMinutes(1), NOW_UTC.minusDays(1)))
                .thenReturn(List.of());

        service.recover();

        InOrder order = inOrder(refundService, bookingRepository);
        order.verify(refundService).retryOwedRefunds();
        order.verify(bookingRepository).findUnconfirmedPayments(NOW_UTC.minusMinutes(1), NOW_UTC.minusDays(1));
    }

    @Test
    void aBookingWhosePaymentWentThroughIsConfirmedAndAnnounced() {
        Booking paid = booking(1, null);
        Booking notPaid = booking(2, null);
        when(bookingRepository.findUnconfirmedPayments(NOW_UTC.minusMinutes(1), NOW_UTC.minusDays(1)))
                .thenReturn(List.of(paid, notPaid));
        when(paymentService.unpaidOrderOf(paid)).thenReturn(List.of(paid));
        when(paymentService.unpaidOrderOf(notPaid)).thenReturn(List.of(notPaid));
        when(paymentService.confirmIfPaid(List.of(paid))).thenReturn(true);
        when(paymentService.confirmIfPaid(List.of(notPaid))).thenReturn(false);

        service.recover();

        verify(eventMessaging).publishSignupConfirmation(List.of(paid));
        verify(eventMessaging, never()).publishSignupConfirmation(List.of(notPaid));
    }

    @Test
    void anOrderIsCheckedOnceAndAnnouncedOnceThoughSeveralOfItsTicketsAreUnconfirmed() {
        Booking first = booking(1, "order-1");
        Booking second = booking(2, "order-1");
        List<Booking> order = List.of(first, second);
        when(bookingRepository.findUnconfirmedPayments(NOW_UTC.minusMinutes(1), NOW_UTC.minusDays(1)))
                .thenReturn(order);
        when(paymentService.unpaidOrderOf(first)).thenReturn(order);
        when(paymentService.confirmIfPaid(order)).thenReturn(true);

        service.recover();

        verify(paymentService).confirmIfPaid(order);
        verify(paymentService, never()).unpaidOrderOf(second);
        verify(eventMessaging).publishSignupConfirmation(order);
    }

    @Test
    void anUnreachablePaymentServiceStopsTheRunUntilNextTime() {
        Booking first = booking(1, null);
        Booking second = booking(2, null);
        when(bookingRepository.findUnconfirmedPayments(NOW_UTC.minusMinutes(1), NOW_UTC.minusDays(1)))
                .thenReturn(List.of(first, second));
        when(paymentService.unpaidOrderOf(first)).thenReturn(List.of(first));
        when(paymentService.confirmIfPaid(List.of(first))).thenThrow(new PaymentUnavailableException("down"));

        service.recover();

        verify(paymentService, never()).confirmIfPaid(List.of(second));
        verifyNoInteractions(eventMessaging);
    }

    private static Booking booking(int id, String orderId) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setOrderId(orderId);
        return booking;
    }
}
