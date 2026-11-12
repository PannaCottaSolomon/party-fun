package events.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import events.client.PaymentClient;
import events.client.PaymentRejectedException;
import events.client.PaymentResponse;
import events.client.PaymentUnavailableException;
import events.client.RefundResponse;
import events.entity.Booking;
import events.entity.BookingStatus;
import events.repository.BookingRepository;

/**
 * Unit tests for {@link BookingRefundService}: a compensation refund is recorded as owed before it is tried, and
 * only the payment service taking it over settles it. Retries look the payment up before refunding again.
 */
@ExtendWith(MockitoExtension.class)
class BookingRefundServiceTest {

    @Mock
    private PaymentClient paymentClient;
    @Mock
    private BookingFinalizerService finalizerService;
    @Mock
    private BookingRepository bookingRepository;
    @InjectMocks
    private BookingRefundService service;

    // ---------- refund (first attempt) ----------

    @Test
    void aRefundIsRecordedAsOwedBeforeItIsTriedThenSettled() {
        Booking booking = cancelledBooking(1);
        when(paymentClient.refund(5)).thenReturn(new RefundResponse(5, "REFUNDED", "Refunded"));

        service.refund(List.of(booking), 5);

        InOrder order = inOrder(finalizerService, paymentClient);
        order.verify(finalizerService).recordRefundOwed(booking, 5);
        order.verify(paymentClient).refund(5);
        order.verify(finalizerService).recordRefundSettled(booking, "REFUNDED");
    }

    @Test
    void aRefundThePaymentServiceSaysWasNeverPaidIsSettled() {
        Booking booking = cancelledBooking(1);
        when(paymentClient.refund(5)).thenThrow(new PaymentRejectedException(409, "Payment 5 was never paid"));

        service.refund(List.of(booking), 5);

        verify(finalizerService).recordRefundSettled(booking, "NOT_PAID");
    }

    @Test
    void aRefusedRefundStaysOwed() {
        Booking booking = cancelledBooking(1);
        when(paymentClient.refund(5)).thenThrow(new PaymentRejectedException(401, "Missing or invalid credentials"));

        service.refund(List.of(booking), 5);

        verify(finalizerService).recordRefundOwed(booking, 5);
        verify(finalizerService, never()).recordRefundSettled(booking, "NOT_PAID");
    }

    @Test
    void aRefundWithoutAnAnswerStaysOwedAndIsNotThrown() {
        Booking booking = cancelledBooking(1);
        when(paymentClient.refund(5)).thenThrow(new PaymentUnavailableException("down"));

        service.refund(List.of(booking), 5);

        verify(finalizerService).recordRefundOwed(booking, 5);
        verifyNotSettled(booking);
    }

    @Test
    void anOrderIsRefundedOnceAndItsOtherBookingsAreSettledTheSameWay() {
        Booking first = cancelledBooking(1);
        Booking second = cancelledBooking(2);
        when(paymentClient.refund(5)).thenReturn(new RefundResponse(5, "REFUNDED", "Refunded"));
        doAnswer(invocation -> {
            first.setRefundedAt(LocalDateTime.of(2026, 10, 29, 10, 0));
            first.setRefundReference("REFUNDED");
            return null;
        }).when(finalizerService).recordRefundSettled(first, "REFUNDED");

        service.refund(List.of(first, second), 5);

        verify(finalizerService).recordRefundOwed(first, 5);
        verify(finalizerService).recordRefundOwed(second, 5);
        verify(paymentClient).refund(5);  // one payment for the whole order: refunded once
        verify(finalizerService).recordRefundSettled(second, "REFUNDED");
    }

    @Test
    void anOrderWhoseRefundIsNotTakenOverYetStaysOwedOnEveryBooking() {
        Booking first = cancelledBooking(1);
        Booking second = cancelledBooking(2);
        when(paymentClient.refund(5)).thenThrow(new PaymentUnavailableException("down"));

        service.refund(List.of(first, second), 5);

        verify(finalizerService).recordRefundOwed(first, 5);
        verify(finalizerService).recordRefundOwed(second, 5);
        verifyNotSettled(first);
        verifyNotSettled(second);
    }

    // ---------- retryOwedRefunds (payment recovery) ----------

    @Test
    void aRetryOfARefundThatWentThroughRecordsItWithoutRefundingAgain() {
        Booking booking = owed(1, 5);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(booking));
        when(paymentClient.findPayments(5)).thenReturn(List.of(new PaymentResponse(5, 5, "REFUNDED", null)));

        service.retryOwedRefunds();

        verify(finalizerService).recordRefundSettled(booking, "REFUNDED");
        verify(paymentClient, never()).refund(5);
    }

    @Test
    void aRetryOfARefundThePaymentServiceGaveUpOnLeavesItToItsDeadLetterQueue() {
        Booking booking = owed(1, 5);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(booking));
        when(paymentClient.findPayments(5)).thenReturn(List.of(new PaymentResponse(5, 5, "REFUND_FAILED", null)));

        service.retryOwedRefunds();

        verify(finalizerService).recordRefundSettled(booking, "REFUND_FAILED");
        verify(paymentClient, never()).refund(5);
    }

    @Test
    void aRetryOfAStillPaidPaymentRefundsIt() {
        Booking booking = owed(1, 5);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(booking));
        when(paymentClient.findPayments(5)).thenReturn(List.of(
                new PaymentResponse(4, 5, "FAILED", null), new PaymentResponse(5, 5, "PAID", null)));
        when(paymentClient.refund(5)).thenReturn(new RefundResponse(5, "PENDING", "Stripe is processing it"));

        service.retryOwedRefunds();

        verify(finalizerService).recordRefundSettled(booking, "PENDING");
    }

    @Test
    void aRetryWhoseLookupIsRejectedRefundsAnyway() {
        Booking booking = owed(1, 5);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(booking));
        when(paymentClient.findPayments(5)).thenThrow(new PaymentRejectedException(404, "Booking 5 not found"));
        when(paymentClient.refund(5)).thenReturn(new RefundResponse(5, "REFUNDED", "Refunded"));

        service.retryOwedRefunds();

        verify(finalizerService).recordRefundSettled(booking, "REFUNDED");
    }

    @Test
    void aRetryWithoutAPaymentBookingRefundsDirectly() {
        Booking booking = owed(1, 5);
        booking.setPaymentBookingId(null);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(booking));
        when(paymentClient.refund(5)).thenReturn(new RefundResponse(5, "ALREADY_REFUNDED", "Already refunded"));

        service.retryOwedRefunds();

        verify(paymentClient, never()).findPayments(5);
        verify(finalizerService).recordRefundSettled(booking, "ALREADY_REFUNDED");
    }

    @Test
    void retriesStopAtThePaymentServiceBeingUnreachable() {
        Booking first = owed(1, 5);
        Booking second = owed(2, 6);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(first, second));
        when(paymentClient.findPayments(5)).thenThrow(new PaymentUnavailableException("down"));

        service.retryOwedRefunds();

        verify(paymentClient, never()).findPayments(6);
        verifyNotSettled(first);
        verifyNotSettled(second);
    }

    @Test
    void retriesStopWhenARefundGetsNoAnswer() {
        Booking first = owed(1, 5);
        Booking second = owed(2, 6);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(first, second));
        when(paymentClient.findPayments(5)).thenReturn(List.of(new PaymentResponse(5, 5, "PAID", null)));
        when(paymentClient.refund(5)).thenThrow(new PaymentUnavailableException("timeout"));

        service.retryOwedRefunds();

        verify(paymentClient, never()).findPayments(6);
    }

    @Test
    void aRefusedRetryMovesOnToTheNextOwedRefund() {
        Booking first = owed(1, 5);
        Booking second = owed(2, 6);
        when(bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(first, second));
        when(paymentClient.findPayments(5)).thenReturn(List.of());
        when(paymentClient.refund(5)).thenThrow(new PaymentRejectedException(403, "Refund endpoint is disabled"));
        when(paymentClient.findPayments(6)).thenReturn(List.of());
        when(paymentClient.refund(6)).thenReturn(new RefundResponse(6, "REFUNDED", "Refunded"));

        service.retryOwedRefunds();

        verifyNotSettled(first);
        verify(finalizerService).recordRefundSettled(second, "REFUNDED");
    }

    private void verifyNotSettled(Booking booking) {
        verify(finalizerService, never()).recordRefundSettled(eq(booking), anyString());
    }

    private static Booking cancelledBooking(int id) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setStatus(BookingStatus.CANCELLED);
        return booking;
    }

    // A booking whose refund of paymentId is owed; its payment-side booking has the same id as the payment
    private static Booking owed(int id, int paymentId) {
        Booking booking = cancelledBooking(id);
        booking.setPaymentBookingId(paymentId);
        booking.setPaymentReference(String.valueOf(paymentId));
        booking.setRefundRequiredAt(LocalDateTime.of(2026, 10, 29, 10, 0));
        return booking;
    }
}
