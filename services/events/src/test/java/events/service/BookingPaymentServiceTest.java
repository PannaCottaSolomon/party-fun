package events.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import events.client.PaymentClient;
import events.client.PaymentRejectedException;
import events.client.PaymentResponse;
import events.client.PaymentUnavailableException;
import events.entity.Booking;
import events.entity.BookingStatus;
import events.repository.BookingRepository;
import events.repository.EventRepository;

/**
 * Unit tests for {@link BookingPaymentService}: every payment outcome, one charge per order (a signup's tickets),
 * reconciliation of lost answers, recovery and the compensation refund. The payment service, the database writes, the
 * refunds and the repository are mocked.
 */
@ExtendWith(MockitoExtension.class)
class BookingPaymentServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("15.00");
    private static final Integer PAYMENT_BOOKING_ID = 7;

    @Mock
    private PaymentClient paymentClient;
    @Mock
    private BookingFinalizerService finalizerService;
    @Mock
    private BookingRefundService refundService;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private EventRepository eventRepository;  // no event found: the payment service gets no title
    @InjectMocks
    private BookingPaymentService service;

    @Test
    void requiresPaymentOnlyForAPositiveAmount() {
        assertThat(BookingPaymentService.requiresPayment(null)).isFalse();
        assertThat(BookingPaymentService.requiresPayment(BigDecimal.ZERO)).isFalse();
        assertThat(BookingPaymentService.requiresPayment(new BigDecimal("0.01"))).isTrue();
    }

    @Test
    void aFreeOrderIsConfirmedWithoutCallingThePaymentService() {
        List<Booking> order = List.of(pendingBooking(1, null), pendingBooking(2, null));
        when(finalizerService.confirm(order, null)).thenReturn(true);

        assertThat(service.pay(order, null)).isTrue();
        verifyNoInteractions(paymentClient);
    }

    @Test
    void aPaidPaymentCreatesAndLinksThePaymentBookingThenConfirms() {
        List<Booking> order = List.of(pendingBooking(1, PRICE));
        when(paymentClient.createBooking(42, 3, 1, PRICE, null)).thenReturn(PAYMENT_BOOKING_ID);
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa")).thenReturn(payment(5, "PAID"));
        when(finalizerService.confirm(order, "5")).thenReturn(true);

        assertThat(service.pay(order, "pm_card_visa")).isTrue();
        verify(finalizerService).linkPaymentBooking(order, PAYMENT_BOOKING_ID);
    }

    @Test
    void anOrderOfSeveralTicketsIsChargedOnceForItsTotal() {
        List<Booking> order = List.of(pendingBooking(1, PRICE), pendingBooking(2, PRICE), pendingBooking(3, PRICE));
        when(paymentClient.createBooking(42, 3, 3, new BigDecimal("45.00"), null)).thenReturn(PAYMENT_BOOKING_ID);
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa")).thenReturn(payment(5, "PAID"));
        when(finalizerService.confirm(order, "5")).thenReturn(true);

        assertThat(service.pay(order, "pm_card_visa")).isTrue();
        verify(finalizerService).linkPaymentBooking(order, PAYMENT_BOOKING_ID);
        verify(paymentClient).createPayment(PAYMENT_BOOKING_ID, "pm_card_visa");  // one charge, not one per ticket
    }

    @Test
    void aRetryReusesTheLinkedPaymentBooking() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa")).thenReturn(payment(5, "PAID"));
        when(finalizerService.confirm(order, "5")).thenReturn(true);

        assertThat(service.pay(order, "pm_card_visa")).isTrue();
        verify(paymentClient, never()).createBooking(any(), any(), anyInt(), any(), any());
        verify(finalizerService, never()).linkPaymentBooking(any(), any());
    }

    @Test
    void aDeclinedCardRecordsTheReasonAndKeepsTheOrderPending() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_chargeDeclined"))
                .thenReturn(new PaymentResponse(6, PAYMENT_BOOKING_ID, "FAILED", "Your card was declined."));

        assertThat(service.pay(order, "pm_card_chargeDeclined")).isFalse();
        verify(finalizerService).recordPaymentFailure(order, "Your card was declined.");
        verify(finalizerService, never()).confirm(any(), any());
    }

    @Test
    void aRejectedPaymentBookingRecordsTheFailureWithoutCharging() {
        List<Booking> order = List.of(pendingBooking(1, PRICE));
        when(paymentClient.createBooking(42, 3, 1, PRICE, null))
                .thenThrow(new PaymentRejectedException(400, "Validation failed"));

        assertThat(service.pay(order, "pm_card_visa")).isFalse();
        verify(finalizerService).recordPaymentFailure(order, "Validation failed");
        verify(finalizerService, never()).linkPaymentBooking(any(), any());
        verify(paymentClient, never()).createPayment(any(), any());
    }

    @Test
    void aPendingPaymentLeavesTheOrderPendingWithoutRecordingAFailure() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_x")).thenReturn(payment(8, "PENDING"));

        assertThat(service.pay(order, "pm_x")).isFalse();
        verify(finalizerService, never()).confirm(any(), any());
        verify(finalizerService, never()).recordPaymentFailure(any(), any());
    }

    @Test
    void aConflictWithAnEarlierPaidPaymentIsReconciledInsteadOfChargingAgain() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa"))
                .thenThrow(new PaymentRejectedException(409, "Booking 7 already has a PAID payment"));
        when(paymentClient.findPayments(PAYMENT_BOOKING_ID)).thenReturn(List.of(payment(4, "FAILED"), payment(5, "PAID")));
        when(finalizerService.confirm(order, "5")).thenReturn(true);

        assertThat(service.pay(order, "pm_card_visa")).isTrue();
        verify(finalizerService, never()).recordPaymentFailure(any(), any());
    }

    @Test
    void aConflictWithoutAPaidPaymentRecordsTheConflictAsTheFailure() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa"))
                .thenThrow(new PaymentRejectedException(409, "Booking 7 already has a PENDING payment"));
        when(paymentClient.findPayments(PAYMENT_BOOKING_ID)).thenReturn(List.of(payment(4, "PENDING")));

        assertThat(service.pay(order, "pm_card_visa")).isFalse();
        verify(finalizerService).recordPaymentFailure(order, "Booking 7 already has a PENDING payment");
    }

    @Test
    void aRejectionOtherThanConflictIsNotReconciled() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa"))
                .thenThrow(new PaymentRejectedException(404, "Booking 7 not found"));

        assertThat(service.pay(order, "pm_card_visa")).isFalse();
        verify(paymentClient, never()).findPayments(any());
        verify(finalizerService).recordPaymentFailure(order, "Booking 7 not found");
    }

    @Test
    void aRejectedLookupDuringReconciliationRecordsTheOriginalConflict() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa"))
                .thenThrow(new PaymentRejectedException(409, "conflict"));
        when(paymentClient.findPayments(PAYMENT_BOOKING_ID)).thenThrow(new PaymentRejectedException(404, "gone"));

        assertThat(service.pay(order, "pm_card_visa")).isFalse();
        verify(finalizerService).recordPaymentFailure(order, "conflict");
    }

    @Test
    void noAnswerFromThePaymentServicePropagatesAndRecordsNothing() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa"))
                .thenThrow(new PaymentUnavailableException("timeout"));

        assertThatThrownBy(() -> service.pay(order, "pm_card_visa")).isInstanceOf(PaymentUnavailableException.class);
        verify(finalizerService, never()).recordPaymentFailure(any(), any());
        verify(finalizerService, never()).confirm(any(), any());
    }

    @Test
    void aPaymentForAnOrderThatStoppedBeingPendingIsRefundedOnce() {
        Booking first = linkedBooking();
        Booking second = pendingBooking(2, PRICE);
        List<Booking> order = List.of(first, second);
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa")).thenReturn(payment(5, "PAID"));
        when(finalizerService.confirm(order, "5")).thenReturn(false);
        when(bookingRepository.findById(1)).thenReturn(Optional.of(withStatus(1, BookingStatus.CANCELLED)));
        when(bookingRepository.findById(2)).thenReturn(Optional.of(withStatus(2, BookingStatus.CANCELLED)));

        assertThat(service.pay(order, "pm_card_visa")).isFalse();
        verify(refundService).refund(order, 5);  // one payment for the whole order: one refund
        verify(finalizerService, never()).recordPaymentFailure(any(), anyString());
        assertThat(first.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(second.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void aBookingAnotherPaymentConfirmedMeanwhileHasThisPaymentRefunded() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa")).thenReturn(payment(5, "PAID"));
        when(finalizerService.confirm(order, "5")).thenReturn(false);
        Booking confirmedByAnother = withStatus(1, BookingStatus.CONFIRMED);
        confirmedByAnother.setPaymentReference("4");
        when(bookingRepository.findById(1)).thenReturn(Optional.of(confirmedByAnother));

        assertThat(service.pay(order, "pm_card_visa")).isFalse();
        verify(refundService).refund(order, 5);  // charged twice for one place: give the second one back
    }

    @Test
    void anOrderThisPaymentAlreadyConfirmedIsNotRefunded() {
        Booking booking = linkedBooking();
        List<Booking> order = List.of(booking);
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa")).thenReturn(payment(5, "PAID"));
        when(finalizerService.confirm(order, "5")).thenReturn(false);
        Booking confirmed = withStatus(1, BookingStatus.CONFIRMED);
        confirmed.setPaymentReference("5");
        confirmed.setConfirmedAt(LocalDateTime.of(2026, 10, 29, 10, 0));
        when(bookingRepository.findById(1)).thenReturn(Optional.of(confirmed));

        assertThat(service.pay(order, "pm_card_visa")).isTrue();
        verifyNoInteractions(refundService);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPaymentReference()).isEqualTo("5");
        assertThat(booking.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 29, 10, 0));
    }

    @Test
    void aPaymentThatConfirmedOnlyPartOfAnOrderIsNeitherReportedConfirmedNorRefunded() {
        List<Booking> order = List.of(linkedBooking(), pendingBooking(2, PRICE));
        when(paymentClient.createPayment(PAYMENT_BOOKING_ID, "pm_card_visa")).thenReturn(payment(5, "PAID"));
        when(finalizerService.confirm(order, "5")).thenReturn(false);
        Booking confirmed = withStatus(1, BookingStatus.CONFIRMED);
        confirmed.setPaymentReference("5");
        when(bookingRepository.findById(1)).thenReturn(Optional.of(confirmed));
        when(bookingRepository.findById(2)).thenReturn(Optional.of(withStatus(2, BookingStatus.CANCELLED)));

        assertThat(service.pay(order, "pm_card_visa")).isFalse();
        verifyNoInteractions(refundService);  // the payment also pays for a confirmed place
    }

    // ---------- unpaidOrderOf ----------

    @Test
    void aBookingWithoutAnOrderIsAnOrderOfItsOwn() {
        Booking booking = pendingBooking(1, PRICE);

        assertThat(service.unpaidOrderOf(booking)).containsExactly(booking);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void theUnpaidOrderIsThePendingTicketsOfTheSignupWithTheGivenBookingItself() {
        Booking booking = inOrder(pendingBooking(2, PRICE));
        Booking stored = inOrder(pendingBooking(2, PRICE));
        Booking sibling = inOrder(pendingBooking(3, PRICE));
        Booking paidSibling = inOrder(withStatus(1, BookingStatus.CONFIRMED));
        when(bookingRepository.findByOrderIdOrderByIdAsc("order-1")).thenReturn(List.of(paidSibling, stored, sibling));

        assertThat(service.unpaidOrderOf(booking)).containsExactly(booking, sibling);
    }

    // ---------- confirmIfPaid (payment recovery) ----------

    @Test
    void recoveryConfirmsAnOrderWhosePaymentWentThrough() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.findPayments(PAYMENT_BOOKING_ID)).thenReturn(List.of(payment(5, "PAID")));
        when(finalizerService.confirm(order, "5")).thenReturn(true);

        assertThat(service.confirmIfPaid(order)).isTrue();
        verify(paymentClient, never()).createPayment(any(), any());
    }

    @Test
    void recoveryLeavesAnOrderWithoutAPaidPaymentAlone() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.findPayments(PAYMENT_BOOKING_ID)).thenReturn(List.of(payment(4, "FAILED")));

        assertThat(service.confirmIfPaid(order)).isFalse();
        verify(finalizerService, never()).confirm(any(), any());
    }

    @Test
    void recoveryTreatsAnUnknownPaymentBookingAsNothingCharged() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.findPayments(PAYMENT_BOOKING_ID)).thenThrow(new PaymentRejectedException(404, "gone"));

        assertThat(service.confirmIfPaid(order)).isFalse();
    }

    @Test
    void recoveryPassesAnUnreachablePaymentServiceOn() {
        List<Booking> order = List.of(linkedBooking());
        when(paymentClient.findPayments(PAYMENT_BOOKING_ID)).thenThrow(new PaymentUnavailableException("down"));

        assertThatThrownBy(() -> service.confirmIfPaid(order)).isInstanceOf(PaymentUnavailableException.class);
    }

    private static Booking pendingBooking(int id, BigDecimal amount) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setUserId(42);
        booking.setEventId(3);
        booking.setTotalAmount(amount);
        booking.setStatus(BookingStatus.PENDING);
        return booking;
    }

    private static Booking linkedBooking() {
        Booking booking = pendingBooking(1, PRICE);
        booking.setPaymentBookingId(PAYMENT_BOOKING_ID);
        return booking;
    }

    private static Booking withStatus(int id, BookingStatus status) {
        Booking booking = pendingBooking(id, PRICE);
        booking.setStatus(status);
        return booking;
    }

    private static Booking inOrder(Booking booking) {
        booking.setOrderId("order-1");
        return booking;
    }

    private static PaymentResponse payment(int paymentId, String status) {
        return new PaymentResponse(paymentId, PAYMENT_BOOKING_ID, status, null);
    }
}
