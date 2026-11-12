package payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import payment.TestData;
import payment.config.PaymentProperties;
import payment.dto.BookingRefundResponse;
import payment.dto.RefundResultResponse;
import payment.dto.RefundResultResponse.Outcome;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.exception.NotFoundException;
import payment.exception.PaymentGatewayException;
import payment.exception.RefundFailedException;
import payment.messaging.PaymentEventPublisher;
import payment.messaging.FailedRefundMessage;
import payment.messaging.RefundFailedEvent;
import payment.messaging.RefundNotification;
import payment.messaging.RefundRequestedMessage;
import payment.repository.BookingRepository;
import payment.repository.PaymentRepository;
import payment.stripe.GatewayRefundResult;
import payment.stripe.PaymentGateway;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final PaymentGatewayException STRIPE_DOWN =
            new PaymentGatewayException("Stripe refund request failed (code=null, status=503)");

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentEventPublisher publisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
    private RefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = service(TestData.properties());
    }

    private RefundService service(PaymentProperties properties) {
        return new RefundService(paymentRepository, bookingRepository, gateway, publisher,
                TransactionOperations.withoutTransaction(), clock, properties);
    }

    /** Makes the mocked repository behave like a tiny database holding these payments. */
    private void store(Payment... payments) {
        for (Payment payment : payments) {
            lenient().when(paymentRepository.findByIdForUpdate(payment.getPaymentId()))
                    .thenReturn(Optional.of(payment));
            lenient().when(paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(
                    payment.getBooking().getBookingId()))
                    .thenAnswer(inv -> Stream.of(payments)
                            .filter(p -> p.getBooking().getBookingId().equals(inv.getArgument(0)))
                            .toList());
        }
    }

    private Payment paidPayment(int paymentId, int bookingId, String intentId) {
        Booking booking = TestData.booking(bookingId, BookingStatus.CONFIRMED, 100 + bookingId, 42, "40.00");
        return TestData.payment(paymentId, booking, PaymentStatus.PAID, intentId);
    }

    @Test
    void successfulRefundMarksRefundedAndPublishesTheRefundNotification() {
        Payment payment = paidPayment(1, 10, "pi_1");
        store(payment);
        when(gateway.refund("pi_1", 4000L, "refund-pi_1")).thenReturn(new GatewayRefundResult("re_1", "succeeded"));

        RefundResultResponse result = refundService.refundPaymentManually(1);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_1");
        assertThat(payment.getRefundedAt()).isNotNull();
        assertThat(payment.getBooking().getStatus()).isEqualTo(BookingStatus.REFUNDED);

        ArgumentCaptor<RefundNotification> notification = ArgumentCaptor.forClass(RefundNotification.class);
        verify(publisher).publishRefundNotification(notification.capture());
        assertThat(notification.getValue().paymentId()).isEqualTo(1);
        assertThat(notification.getValue().registrationId()).isEqualTo("10");
        assertThat(notification.getValue().amountCents()).isEqualTo(4000L);
        assertThat(notification.getValue().currency()).isEqualTo("SGD");
        assertThat(notification.getValue().refundId()).isEqualTo("re_1");
        verify(publisher, never()).publishAdminAlert(any());
    }

    @Test
    void failedRefundIsRetriedUntilItSucceeds() {
        Payment payment = paidPayment(1, 10, "pi_1");
        store(payment);
        when(gateway.refund(anyString(), anyLong(), anyString()))
                .thenThrow(STRIPE_DOWN)
                .thenThrow(STRIPE_DOWN)
                .thenReturn(new GatewayRefundResult("re_1", "succeeded"));

        RefundResultResponse result = refundService.refundAndReport(1, RefundTrigger.MANUAL, null);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(gateway, times(3)).refund(anyString(), anyLong(), anyString());
        verify(publisher).publishRefundNotification(any());
        verify(publisher, never()).publishAdminAlert(any());
    }

    @Test
    void retryUsesTheSameIdempotencyKeyEveryTime() {
        store(paidPayment(1, 10, "pi_1"));
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        refundService.refundAndReport(1, RefundTrigger.MANUAL, null);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(3)).refund(eq("pi_1"), eq(4000L), keys.capture());
        assertThat(keys.getAllValues()).containsOnly("refund-pi_1");
    }

    @Test
    void stopsAfterExactlyThreeAttemptsThenMarksRefundFailedAndAlertsTheAdmin() {
        Payment payment = paidPayment(1, 10, "pi_1");
        store(payment);
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        RefundResultResponse result = refundService.refundAndReport(1, RefundTrigger.EVENT_CANCELLED, "req-1");

        verify(gateway, times(3)).refund(anyString(), anyLong(), anyString());
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        // Retry Saga, not rollback: the booking is not reactivated or otherwise changed.
        assertThat(payment.getBooking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        ArgumentCaptor<RefundFailedEvent> failure = ArgumentCaptor.forClass(RefundFailedEvent.class);
        verify(publisher).publishAdminAlert(failure.capture());
        RefundFailedEvent dlqMessage = failure.getValue();
        assertThat(dlqMessage.eventType()).isEqualTo("refund.failed");
        assertThat(dlqMessage.paymentId()).isEqualTo(1);
        assertThat(dlqMessage.bookingId()).isEqualTo(10);
        assertThat(dlqMessage.eventId()).isEqualTo(42);
        assertThat(dlqMessage.userId()).isEqualTo(110);
        assertThat(dlqMessage.amount()).isEqualByComparingTo("40.00");
        assertThat(dlqMessage.amountCents()).isEqualTo(4000L);
        assertThat(dlqMessage.stripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(dlqMessage.failureReason()).contains("status=503");
        assertThat(dlqMessage.retryCount()).isEqualTo(3);
        assertThat(dlqMessage.trigger()).isEqualTo("EVENT_CANCELLED");
        assertThat(dlqMessage.requestId()).isEqualTo("req-1");
        assertThat(dlqMessage.timestamp()).isEqualTo(Instant.parse("2026-10-06T10:00:00Z"));
        verify(publisher, never()).publishRefundNotification(any());
        // Application-managed routing: one individual message for this payment, published to the refund DLX.
        ArgumentCaptor<FailedRefundMessage> dlq = ArgumentCaptor.forClass(FailedRefundMessage.class);
        verify(publisher, times(1)).publishFailedRefund(dlq.capture());
        assertThat(dlq.getValue().paymentId()).isEqualTo(1);
        assertThat(dlq.getValue().refundAmountCents()).isEqualTo(4000L);
        assertThat(dlq.getValue().retryCount()).isEqualTo(3);
        assertThat(dlq.getValue().failureReason()).contains("status=503");
        // MySQL is authoritative and keeps the same failure details.
        assertThat(payment.getRefundFailureReason()).contains("status=503");
        assertThat(payment.getRefundAttempts()).isEqualTo(3);
        assertThat(payment.getRefundFailedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 6, 10, 0));
    }

    @Test
    void maxAttemptsIsConfigurable() {
        store(paidPayment(1, 10, "pi_1"));
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        service(TestData.properties(5, true)).refundAndReport(1, RefundTrigger.MANUAL, null);

        verify(gateway, times(5)).refund(anyString(), anyLong(), anyString());
    }

    @Test
    void manualRefundThatExhaustsRetriesBecomesRefundFailedException() {
        store(paidPayment(1, 10, "pi_1"));
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        assertThatThrownBy(() -> refundService.refundPaymentManually(1))
                .isInstanceOf(RefundFailedException.class)
                .hasMessageContaining("3 attempts")
                .hasMessageContaining("dead-letter");
        verify(publisher).publishAdminAlert(any());
        ArgumentCaptor<FailedRefundMessage> dlq = ArgumentCaptor.forClass(FailedRefundMessage.class);
        verify(publisher).publishFailedRefund(dlq.capture());
        assertThat(dlq.getValue().paymentId()).isEqualTo(1);
        assertThat(dlq.getValue().bookingId()).isEqualTo(10);
        assertThat(dlq.getValue().refundAmountCents()).isEqualTo(4000L);
        assertThat(dlq.getValue().stripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(dlq.getValue().trigger()).isEqualTo("MANUAL");
    }

    @Test
    void alreadyRefundedPaymentIsNotSentToStripeAgain() {
        Payment payment = paidPayment(1, 10, "pi_1");
        payment.startRefund();
        payment.markRefunded("re_old", java.time.LocalDateTime.now());
        store(payment);

        RefundResultResponse result = refundService.refundPaymentManually(1);

        assertThat(result.outcome()).isEqualTo(Outcome.ALREADY_REFUNDED);
        assertThat(result.stripeRefundId()).isEqualTo("re_old");
        assertThat(result.attempts()).isZero();
        verifyNoInteractions(gateway, publisher);
    }

    @Test
    void repeatedRefundRequestRefundsOnlyOnce() {
        store(paidPayment(1, 10, "pi_1"));
        when(gateway.refund(anyString(), anyLong(), anyString())).thenReturn(new GatewayRefundResult("re_1", "succeeded"));

        refundService.refundPaymentManually(1);
        RefundResultResponse second = refundService.refundPaymentManually(1);

        assertThat(second.outcome()).isEqualTo(Outcome.ALREADY_REFUNDED);
        verify(gateway, times(1)).refund(anyString(), anyLong(), anyString());
        verify(publisher, times(1)).publishRefundNotification(any());
    }

    @Test
    void pendingPaymentCannotBeRefunded() {
        Booking booking = TestData.booking(10, BookingStatus.PENDING, 1, 42, "40.00");
        store(TestData.payment(1, booking, PaymentStatus.PENDING, null));

        assertThatThrownBy(() -> refundService.refundPaymentManually(1))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("PENDING");
        verifyNoInteractions(gateway, publisher);
    }

    @Test
    void unknownPaymentIsNotFound() {
        when(paymentRepository.findByIdForUpdate(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.refundPaymentManually(99)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void refundFailedPaymentCanBeRetriedManuallyAfterDlqRecovery() {
        Payment payment = paidPayment(1, 10, "pi_1");
        payment.startRefund();
        payment.markRefundFailed("Stripe refund request failed", 3, java.time.LocalDateTime.now());
        store(payment);
        // A retry is a new refund round with a new key: Stripe would replay the failed round's answer.
        when(gateway.refund("pi_1", 4000L, "refund-pi_1-retry-1"))
                .thenReturn(new GatewayRefundResult("re_1", "succeeded"));

        RefundResultResponse result = refundService.refundPaymentManually(1);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void eventCancellationRefundsEveryEligiblePaymentIndividuallyInsideTheService() {
        Payment paid = paidPayment(1, 10, "pi_1");
        Payment resuming = paidPayment(2, 20, "pi_2");
        resuming.startRefund(); // claimed by an earlier run that stopped before Stripe answered
        Payment alreadyFailed = paidPayment(3, 30, "pi_3");
        alreadyFailed.startRefund();
        alreadyFailed.markRefundFailed("Stripe refund request failed", 3, java.time.LocalDateTime.now());
        Booking unpaid = TestData.booking(40, BookingStatus.PENDING, 140, 42, "15.00");
        stubEvent(42, List.of(paid.getBooking(), resuming.getBooking(), alreadyFailed.getBooking(), unpaid),
                List.of(paid, resuming, alreadyFailed));
        store(paid, resuming, alreadyFailed);
        when(gateway.refund("pi_1", 4000L, "refund-pi_1")).thenReturn(new GatewayRefundResult("re_1", "succeeded"));
        when(gateway.refund("pi_2", 4000L, "refund-pi_2")).thenReturn(new GatewayRefundResult("re_2", "succeeded"));

        EventRefundSummary summary = refundService.refundEvent(new RefundRequestedMessage(42, "cancel-42"));

        // PAID and interrupted REFUND_PENDING payments are refunded; REFUND_FAILED ones are already in the DLQ.
        assertThat(summary.results()).extracting(RefundResultResponse::paymentId).containsExactly(1, 2);
        assertThat(summary.refunded()).isEqualTo(2);
        assertThat(summary.failed()).isZero();
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(resuming.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(alreadyFailed.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        verify(gateway, never()).refund(eq("pi_3"), anyLong(), anyString());
        verify(publisher, times(2)).publishRefundNotification(any());
        verify(publisher, never()).publishFailedRefund(any());
        // The event stays cancelled: open bookings are cancelled and never reactivated.
        assertThat(unpaid.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void mixedResultsLeaveSuccessfulRefundsAloneAndSendEachFailureIndividuallyToTheDlq() {
        Payment first = paidPayment(1, 10, "pi_1");
        Payment failing = paidPayment(2, 20, "pi_2");
        Payment third = paidPayment(3, 30, "pi_3");
        stubEvent(42, List.of(first.getBooking(), failing.getBooking(), third.getBooking()),
                List.of(first, failing, third));
        store(first, failing, third);
        when(gateway.refund(eq("pi_1"), anyLong(), anyString())).thenReturn(new GatewayRefundResult("re_1", "succeeded"));
        when(gateway.refund(eq("pi_2"), anyLong(), anyString())).thenThrow(PaymentGatewayException.permanent(
                "Stripe refund request failed (code=charge_disputed, status=400)", "charge_disputed"));
        when(gateway.refund(eq("pi_3"), anyLong(), anyString())).thenReturn(new GatewayRefundResult("re_3", "succeeded"));

        EventRefundSummary summary = refundService.refundEvent(new RefundRequestedMessage(42, "cancel-42"));

        assertThat(summary.refunded()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(third.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(failing.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        ArgumentCaptor<FailedRefundMessage> dlq = ArgumentCaptor.forClass(FailedRefundMessage.class);
        verify(publisher, times(1)).publishFailedRefund(dlq.capture());
        assertThat(dlq.getValue().paymentId()).isEqualTo(2);
        assertThat(dlq.getValue().eventId()).isEqualTo(42);
        assertThat(dlq.getValue().trigger()).isEqualTo("EVENT_CANCELLED");
        assertThat(dlq.getValue().errorCode()).isEqualTo("charge_disputed");
        verify(publisher, times(1)).publishAdminAlert(any());
        verify(publisher, times(2)).publishRefundNotification(any());
    }

    @Test
    void duplicateCancellationRefundsNothingTwiceAndAddsNothingToTheDlq() {
        Payment refunded = paidPayment(1, 10, "pi_1");
        refunded.startRefund();
        refunded.markRefunded("re_1", java.time.LocalDateTime.now());
        Payment failed = paidPayment(2, 20, "pi_2");
        failed.startRefund();
        failed.markRefundFailed("Stripe refund request failed", 3, java.time.LocalDateTime.now());
        stubEvent(42, List.of(refunded.getBooking(), failed.getBooking()), List.of(refunded, failed));

        EventRefundSummary again = refundService.refundEvent(new RefundRequestedMessage(42, "cancel-42"));

        assertThat(again.results()).isEmpty();
        verifyNoInteractions(gateway, publisher);
    }

    @Test
    void dlqPublishFailureDoesNotStopTheOtherRefundsAndTheFailureStaysInMysql() {
        Payment failing = paidPayment(1, 10, "pi_1");
        Payment next = paidPayment(2, 20, "pi_2");
        stubEvent(42, List.of(failing.getBooking(), next.getBooking()), List.of(failing, next));
        store(failing, next);
        when(gateway.refund(eq("pi_1"), anyLong(), anyString())).thenThrow(STRIPE_DOWN);
        when(gateway.refund(eq("pi_2"), anyLong(), anyString())).thenReturn(new GatewayRefundResult("re_2", "succeeded"));
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpException("broker unavailable"))
                .when(publisher).publishFailedRefund(any());

        EventRefundSummary summary = refundService.refundEvent(new RefundRequestedMessage(42, "cancel-42"));

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.refunded()).isEqualTo(1);
        assertThat(failing.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(failing.getRefundFailureReason()).isNotBlank(); // the Admin dashboard still lists it
        assertThat(next.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void unexpectedErrorPropagatesSoTheCancellationMessageIsRedeliveredNotLost() {
        Payment paid = paidPayment(1, 10, "pi_1");
        stubEvent(42, List.of(paid.getBooking()), List.of(paid));
        when(paymentRepository.findByIdForUpdate(1)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> refundService.refundEvent(new RefundRequestedMessage(42, "cancel-42")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bookingRefundThatFailsSendsThatPaymentThroughTheDeadLetterExchange() {
        Booking booking = TestData.booking(10, BookingStatus.CONFIRMED, 1, 42, "40.00");
        Payment failing = TestData.payment(1, booking, PaymentStatus.PAID, "pi_fail");
        store(failing);
        when(bookingRepository.existsById(10)).thenReturn(true);
        when(gateway.refund(eq("pi_fail"), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        BookingRefundResponse response = refundService.refundBooking(10);

        assertThat(response.results()).extracting(RefundResultResponse::outcome).containsExactly(Outcome.FAILED);
        verify(publisher).publishAdminAlert(any());
        verify(publisher).publishFailedRefund(any());
    }

    @Test
    void bookingRefundReportsEachPaymentSeparately() {
        Booking booking = TestData.booking(10, BookingStatus.CONFIRMED, 1, 42, "40.00");
        Payment declined = TestData.payment(1, booking, PaymentStatus.FAILED, "pi_declined");
        Payment paid = TestData.payment(2, booking, PaymentStatus.PAID, "pi_paid");
        store(declined, paid);
        when(bookingRepository.existsById(10)).thenReturn(true);
        when(gateway.refund(eq("pi_paid"), anyLong(), anyString())).thenReturn(new GatewayRefundResult("re_2", "succeeded"));

        BookingRefundResponse response = refundService.refundBooking(10);

        assertThat(response.results()).extracting(RefundResultResponse::outcome)
                .containsExactly(Outcome.SKIPPED, Outcome.REFUNDED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.REFUNDED);
    }

    @Test
    void bookingWithoutPaidPaymentsCannotBeRefunded() {
        Booking booking = TestData.booking(10, BookingStatus.PENDING, 1, 42, "40.00");
        store(TestData.payment(1, booking, PaymentStatus.PENDING, null));
        when(bookingRepository.existsById(10)).thenReturn(true);

        assertThatThrownBy(() -> refundService.refundBooking(10)).isInstanceOf(InvalidStateException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void refundOfUnknownBookingIsNotFound() {
        when(bookingRepository.existsById(10)).thenReturn(false);

        assertThatThrownBy(() -> refundService.refundBooking(10)).isInstanceOf(NotFoundException.class);
    }

    /** Repository lookups by event that respect the requested statuses, like the real queries. */
    @SuppressWarnings("unchecked")
    private void stubEvent(int eventId, List<Booking> bookings, List<Payment> payments) {
        when(bookingRepository.findByEventIdAndStatusIn(eq(eventId), any())).thenAnswer(inv -> bookings.stream()
                .filter(b -> ((Collection<BookingStatus>) inv.getArgument(1)).contains(b.getStatus()))
                .toList());
        when(paymentRepository.findByEventIdAndStatusIn(eq(eventId), any())).thenAnswer(inv -> payments.stream()
                .filter(p -> ((Collection<PaymentStatus>) inv.getArgument(1)).contains(p.getStatus()))
                .toList());
    }

    // ---------------------------------------------------------------- Admin recovery building blocks

    private Payment refundFailedPayment(int paymentId) {
        Payment payment = paidPayment(paymentId, 10, "pi_" + paymentId);
        payment.startRefund();
        payment.markRefundFailed("Stripe refund request failed", 3, java.time.LocalDateTime.now());
        return payment;
    }

    @Test
    void recoveryClaimIsExclusive() {
        Payment payment = refundFailedPayment(1);
        store(payment);

        RefundService.RecoveryClaim claim = refundService.claimForRecovery(1);

        assertThat(claim.alreadyRefunded()).isFalse();
        assertThat(claim.paymentIntentId()).isEqualTo("pi_1");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getRefundRound()).isEqualTo(1);
        // A second Admin (or the same one clicking twice) cannot claim it while it is being recovered.
        assertThatThrownBy(() -> refundService.claimForRecovery(1))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("already in progress");
    }

    @Test
    void onlyRefundFailedPaymentsCanBeClaimedAndRefundedOnesAreReported() {
        Payment paid = paidPayment(1, 10, "pi_1");
        Payment refunded = paidPayment(2, 11, "pi_2");
        refunded.startRefund();
        refunded.markRefunded("re_2", java.time.LocalDateTime.now());
        store(paid, refunded);

        assertThatThrownBy(() -> refundService.claimForRecovery(1))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("PAID");
        assertThat(refundService.claimForRecovery(2).alreadyRefunded()).isTrue();
        verifyNoInteractions(publisher, gateway);
    }

    @Test
    void recoveredRefundThatSucceedsPublishesTheRefundNotification() {
        Payment payment = refundFailedPayment(1);
        store(payment);
        when(gateway.refund("pi_1", 4000L, "refund-pi_1-retry-1"))
                .thenReturn(new GatewayRefundResult("re_1", "succeeded"));

        RefundResultResponse result = refundService.refundForRecovery(1, "admin-retry-1-x").result();

        assertThat(result.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundFailureReason()).isNull();
        ArgumentCaptor<RefundNotification> notification = ArgumentCaptor.forClass(RefundNotification.class);
        verify(publisher).publishRefundNotification(notification.capture());
        assertThat(notification.getValue().refundId()).isEqualTo("re_1");
    }

    @Test
    void recoveredRefundThatFailsAgainAlertsTheAdminButPublishesNoNewDlqMessage() {
        Payment payment = refundFailedPayment(1);
        store(payment);
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        RefundService.RefundAttemptResult attempt = refundService.refundForRecovery(1, "admin-retry-1-y");

        assertThat(attempt.result().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(attempt.failure()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        verify(gateway, times(3)).refund(anyString(), anyLong(), anyString());
        ArgumentCaptor<RefundFailedEvent> failure = ArgumentCaptor.forClass(RefundFailedEvent.class);
        verify(publisher).publishAdminAlert(failure.capture());
        assertThat(failure.getValue().trigger()).isEqualTo("ADMIN_RETRY");
        // The Admin recovery holds the existing DLQ message and puts it back: no duplicate.
        verify(publisher, never()).publishFailedRefund(any());
    }

    // ---------------------------------------------------------------- Stripe statuses and retry classification

    @Test
    void notificationUsesTheCheckoutSnapshot() {
        Payment payment = paidPayment(1, 10, "pi_1");
        TestData.withCheckoutSnapshot(payment.getBooking(), "evb-10", "pat@smu.edu.sg", "Neon Party");
        store(payment);
        when(gateway.refund("pi_1", 4000L, "refund-pi_1")).thenReturn(new GatewayRefundResult("re_1", "succeeded"));

        refundService.refundAndReport(1, RefundTrigger.EVENT_CANCELLED, "cancel-42");

        ArgumentCaptor<RefundNotification> notification = ArgumentCaptor.forClass(RefundNotification.class);
        verify(publisher).publishRefundNotification(notification.capture());
        assertThat(notification.getValue()).isEqualTo(new RefundNotification(1, "evb-10", 110, "Neon Party", 4000L,
                "SGD", "re_1"));  // the booking's user id, never an email address
        assertThat(notification.getValue().hasRequiredFields()).isTrue();
    }

    @Test
    void pendingStripeRefundIsNotMarkedRefunded() {
        Payment payment = paidPayment(1, 10, "pi_1");
        store(payment);
        when(gateway.refund("pi_1", 4000L, "refund-pi_1")).thenReturn(new GatewayRefundResult("re_9", "pending"));

        RefundResultResponse result = refundService.refundAndReport(1, RefundTrigger.EVENT_CANCELLED, "cancel-42");

        assertThat(result.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_9");
        assertThat(payment.getRefundedAt()).isNull();
        verify(gateway, times(1)).refund(anyString(), anyLong(), anyString());
        verify(publisher, never()).publishRefundNotification(any());
        verify(publisher, never()).publishAdminAlert(any());
    }

    @Test
    void manualRefundThatStripeLeavesPendingIsNotAnError() {
        store(paidPayment(1, 10, "pi_1"));
        when(gateway.refund("pi_1", 4000L, "refund-pi_1")).thenReturn(new GatewayRefundResult("re_9", "pending"));

        RefundResultResponse result = refundService.refundPaymentManually(1);

        assertThat(result.outcome()).isEqualTo(Outcome.PENDING);
        verify(publisher, never()).publishFailedRefund(any());
    }

    @Test
    void permanentStripeErrorIsNotRetried() {
        Payment payment = paidPayment(1, 10, "pi_1");
        store(payment);
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(PaymentGatewayException.permanent(
                "Stripe refund request failed (code=charge_already_refunded, status=400)", "charge_already_refunded"));

        RefundResultResponse result = refundService.refundAndReport(1, RefundTrigger.EVENT_CANCELLED, "cancel-42");

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        verify(gateway, times(1)).refund(anyString(), anyLong(), anyString());
        ArgumentCaptor<RefundFailedEvent> failure = ArgumentCaptor.forClass(RefundFailedEvent.class);
        verify(publisher).publishAdminAlert(failure.capture());
        assertThat(failure.getValue().retryable()).isFalse();
        assertThat(failure.getValue().errorCode()).isEqualTo("charge_already_refunded");
        assertThat(failure.getValue().retryCount()).isEqualTo(1);
    }

    @Test
    void transientStripeErrorIsRetriedThenRecordedAsRetryable() {
        store(paidPayment(1, 10, "pi_1"));
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        refundService.refundAndReport(1, RefundTrigger.EVENT_CANCELLED, "cancel-42");

        verify(gateway, times(3)).refund(anyString(), anyLong(), anyString());
        ArgumentCaptor<RefundFailedEvent> failure = ArgumentCaptor.forClass(RefundFailedEvent.class);
        verify(publisher).publishAdminAlert(failure.capture());
        assertThat(failure.getValue().retryable()).isTrue();
        assertThat(failure.getValue().retryCount()).isEqualTo(3);
    }

    @Test
    void eventCancellationWithoutRequestIdUsesADeterministicOne() {
        Payment paid = paidPayment(1, 10, "pi_1");
        stubEvent(42, List.of(paid.getBooking()), List.of(paid));
        store(paid);
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(STRIPE_DOWN);

        refundService.refundEvent(new RefundRequestedMessage(42, null));

        ArgumentCaptor<RefundFailedEvent> failure = ArgumentCaptor.forClass(RefundFailedEvent.class);
        verify(publisher).publishAdminAlert(failure.capture());
        assertThat(failure.getValue().requestId()).isEqualTo("cancel-event-42");
    }

    // ---------------------------------------------------------------- refund rounds (idempotency keys)

    @Test
    void idempotencyKeyIsStableWithinARoundAndNewForEachRetryRound() {
        assertThat(RefundService.idempotencyKey("pi_7", 0)).isEqualTo("refund-pi_7");
        assertThat(RefundService.idempotencyKey("pi_7", 1)).isEqualTo("refund-pi_7-retry-1");
        assertThat(RefundService.idempotencyKey("pi_7", 2)).isEqualTo("refund-pi_7-retry-2");
    }

    @Test
    void eachRetryOfARefundFailedPaymentUsesANewIdempotencyKey() {
        Payment payment = refundFailedPayment(1);
        store(payment);
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(PaymentGatewayException.permanent(
                "Stripe refund request failed (code=charge_already_refunded, status=400)", "charge_already_refunded"));

        refundService.refundForRecovery(1, "admin-retry-1-a");
        refundService.refundForRecovery(1, "admin-retry-1-b");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).refund(eq("pi_1"), eq(4000L), keys.capture());
        assertThat(keys.getAllValues()).containsExactly("refund-pi_1-retry-1", "refund-pi_1-retry-2");
        assertThat(payment.getRefundRound()).isEqualTo(2);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
    }

    @Test
    void interruptedRetryRoundResumesWithItsOwnKey() {
        Payment payment = refundFailedPayment(1);
        payment.startRefund(); // round 1 claimed, then the service stopped before Stripe answered
        store(payment);
        when(gateway.refund("pi_1", 4000L, "refund-pi_1-retry-1"))
                .thenReturn(new GatewayRefundResult("re_2", "succeeded"));

        RefundResultResponse result = refundService.refundAndReport(1, RefundTrigger.RECOVERY, "recovery-1");

        assertThat(result.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(payment.getRefundRound()).isEqualTo(1);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_2");
    }
}
