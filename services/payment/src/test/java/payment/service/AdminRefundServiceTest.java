package payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import payment.TestData;
import payment.dto.AdminRetryResponse;
import payment.dto.FailedRefundsResponse;
import payment.dto.RefundResultResponse.Outcome;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.exception.PaymentGatewayException;
import payment.messaging.FailedRefundMessage;
import payment.messaging.PaymentEventPublisher;
import payment.messaging.RefundDeadLetterQueue;
import payment.messaging.RefundDeadLetterQueue.DlqEntry;
import payment.messaging.RefundDeadLetterQueue.DlqStats;
import payment.messaging.RefundDeadLetterQueue.Settlement;
import payment.repository.BookingRepository;
import payment.repository.PaymentRepository;
import payment.stripe.GatewayRefundResult;
import payment.stripe.PaymentGateway;

/** Admin recovery of failed refunds: Stripe checked first, the DLQ message removed only after success. */
@ExtendWith(MockitoExtension.class)
class AdminRefundServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentEventPublisher publisher;
    @Mock
    private RefundDeadLetterQueue deadLetterQueue;

    private AdminRefundService admin;
    /** What the last recovery told the DLQ to do with the held messages: true = remove, false = keep. */
    private Boolean removed;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
        RefundService refundService = new RefundService(paymentRepository, bookingRepository, gateway, publisher,
                TransactionOperations.withoutTransaction(), clock, TestData.properties());
        admin = new AdminRefundService(paymentRepository, refundService, gateway, deadLetterQueue);
        lenient().when(deadLetterQueue.stats()).thenReturn(new DlqStats("payments.refunds.dlq", 1, 0L, 1,
                "management-api"));
    }

    /** The mocked DLQ holds {@code held} messages for the payment and records the decision. */
    @SuppressWarnings("unchecked")
    private void dlqHolds(int held) {
        when(deadLetterQueue.settle(anyInt(), any())).thenAnswer(invocation -> {
            Settlement<Object> settlement = ((IntFunction<Settlement<Object>>) invocation.getArgument(1)).apply(held);
            removed = settlement.removeFromQueue();
            return settlement.result();
        });
    }

    private Payment failedRefund(int paymentId) {
        Booking booking = TestData.booking(10 + paymentId, BookingStatus.CANCELLED, 1, 42, "30.00");
        Payment payment = TestData.payment(paymentId, booking, PaymentStatus.PAID, "pi_" + paymentId);
        payment.startRefund();
        payment.markRefundFailed("Stripe refund request failed (code=null, status=503)", 3,
                LocalDateTime.of(2026, 10, 23, 9, 0));
        lenient().when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        lenient().when(paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(booking.getBookingId()))
                .thenReturn(List.of(payment));
        return payment;
    }

    @Test
    void successfulRecoveryRefundsAgainNotifiesTheParticipantAndRemovesTheDlqMessage() {
        Payment payment = failedRefund(101);
        dlqHolds(1);
        when(gateway.listRefunds("pi_101")).thenReturn(List.of(new GatewayRefundResult("re_old", "failed")));
        when(gateway.refund("pi_101", 3000L, "refund-pi_101-retry-1"))
                .thenReturn(new GatewayRefundResult("re_new", "succeeded"));

        AdminRetryResponse response = admin.retry(101);

        assertThat(response.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(response.dlqMessagesFound()).isEqualTo(1);
        assertThat(response.dlqMessagesRemoved()).isEqualTo(1);
        assertThat(removed).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_new");
        verify(publisher).publishRefundNotification(any());
        verify(publisher, never()).publishFailedRefund(any());
    }

    @Test
    void failedRecoveryKeepsTheSameDlqMessageAndRecordsTheNewReason() {
        Payment payment = failedRefund(102);
        dlqHolds(1);
        when(gateway.listRefunds("pi_102")).thenReturn(List.of());
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(PaymentGatewayException.permanent(
                "Stripe refund request failed (code=charge_disputed, status=400)", "charge_disputed"));

        AdminRetryResponse response = admin.retry(102);

        assertThat(response.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(response.dlqMessagesRemoved()).isZero();
        assertThat(removed).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(payment.getRefundFailureReason()).contains("charge_disputed");
        verify(publisher).publishAdminAlert(any());
        verify(publisher, never()).publishFailedRefund(any()); // no duplicate DLQ entry
    }

    @Test
    void refundThatAlreadySucceededAtStripeIsRecordedWithoutRefundingTwice() {
        Payment payment = failedRefund(103);
        dlqHolds(1);
        when(gateway.listRefunds("pi_103")).thenReturn(List.of(new GatewayRefundResult("re_ok", "succeeded")));

        AdminRetryResponse response = admin.retry(103);

        assertThat(response.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(removed).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_ok");
        verify(gateway, never()).refund(anyString(), anyLong(), anyString());
        verify(publisher).publishRefundNotification(any());
    }

    @Test
    void refundStillPendingAtStripeIsLeftToItsWebhook() {
        Payment payment = failedRefund(104);
        dlqHolds(1);
        when(gateway.listRefunds("pi_104")).thenReturn(List.of(new GatewayRefundResult("re_wait", "pending")));

        AdminRetryResponse response = admin.retry(104);

        assertThat(response.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(removed).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_wait");
        verify(gateway, never()).refund(anyString(), anyLong(), anyString());
    }

    @Test
    void paymentAlreadyRefundedOnlyClearsItsStaleDlqMessages() {
        Payment payment = failedRefund(105);
        payment.startRefund();
        payment.markRefunded("re_done", LocalDateTime.now());
        dlqHolds(2);

        AdminRetryResponse response = admin.retry(105);

        assertThat(response.outcome()).isEqualTo(Outcome.ALREADY_REFUNDED);
        assertThat(response.dlqMessagesRemoved()).isEqualTo(2);
        assertThat(removed).isTrue();
        verify(gateway, never()).listRefunds(any());
        verify(gateway, never()).refund(anyString(), anyLong(), anyString());
    }

    @Test
    void secondAdminCannotRecoverTheSameRefundAtTheSameTime() {
        Payment payment = failedRefund(106);
        payment.startRefund(); // the first Admin's claim

        assertThatThrownBy(() -> admin.retry(106)).isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("already in progress");
        verify(deadLetterQueue, never()).settle(anyInt(), any());
    }

    @Test
    void stripeUnreachableGivesTheClaimBackAndKeepsTheMessage() {
        Payment payment = failedRefund(107);
        dlqHolds(1);
        when(gateway.listRefunds("pi_107")).thenThrow(new PaymentGatewayException("Stripe refund lookup failed"));

        AdminRetryResponse response = admin.retry(107);

        assertThat(response.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(removed).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(payment.getRefundFailureReason()).contains("Could not check");
    }

    @Test
    void failureWithoutAnyDlqMessageGetsOneSoItIsRepresented() {
        failedRefund(108);
        dlqHolds(0); // e.g. the original DLQ publish failed
        when(gateway.listRefunds("pi_108")).thenReturn(List.of());
        when(gateway.refund(anyString(), anyLong(), anyString())).thenThrow(PaymentGatewayException.permanent(
                "Stripe refund request failed (code=charge_disputed, status=400)", "charge_disputed"));

        admin.retry(108);

        ArgumentCaptor<FailedRefundMessage> published = ArgumentCaptor.forClass(FailedRefundMessage.class);
        verify(publisher).publishFailedRefund(published.capture());
        assertThat(published.getValue().paymentId()).isEqualTo(108);
        assertThat(published.getValue().trigger()).isEqualTo("ADMIN_RETRY");
    }

    @Test
    void failedListComesFromMysqlCorrelatedWithTheDlq() {
        Payment inDlq = failedRefund(101);
        Payment notInDlq = failedRefund(102);
        when(paymentRepository.findWithBookingByStatus(PaymentStatus.REFUND_FAILED)).thenReturn(List.of(inDlq, notInDlq));
        when(deadLetterQueue.peek(eq(AdminRefundService.PEEK_LIMIT))).thenReturn(List.of(
                new DlqEntry(101, null, false), new DlqEntry(101, null, false), new DlqEntry(999, null, false)));

        FailedRefundsResponse response = admin.failedRefunds();

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.failedRefunds()).extracting(v -> v.paymentId() + ":" + v.dlqMessages())
                .containsExactly("101:2", "102:0");
        assertThat(response.failedRefunds().get(0).failureReason()).contains("status=503");
        assertThat(response.failedRefunds().get(0).amountCents()).isEqualTo(3000L);
        assertThat(response.failedRefunds().get(0).refundAttempts()).isEqualTo(3);
        assertThat(response.unmatchedDlqMessages()).containsExactly(999);
        assertThat(response.dlq().total()).isEqualTo(1);
    }
}
