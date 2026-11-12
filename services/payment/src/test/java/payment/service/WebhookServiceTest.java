package payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import payment.TestData;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.InvalidWebhookException;
import payment.messaging.PaymentEventPublisher;
import payment.messaging.FailedRefundMessage;
import payment.messaging.RefundFailedEvent;
import payment.repository.BookingRepository;
import payment.repository.PaymentRepository;
import payment.service.WebhookService.Action;
import payment.stripe.GatewayWebhookEvent;
import payment.stripe.PaymentGateway;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentEventPublisher publisher;

    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
        TransactionOperations tx = TransactionOperations.withoutTransaction();
        RefundService refundService = new RefundService(paymentRepository, bookingRepository, gateway, publisher, tx,
                clock, TestData.properties());
        webhookService = new WebhookService(gateway, paymentRepository, refundService, publisher, tx, clock);
    }

    private void receive(GatewayWebhookEvent event) {
        when(gateway.parseWebhook(anyString(), any())).thenReturn(event);
    }

    private Payment stored(PaymentStatus status, BookingStatus bookingStatus) {
        Booking booking = TestData.booking(10, bookingStatus, 1, 42, "40.00");
        Payment payment = TestData.payment(1, booking, status, "pi_1");
        lenient().when(paymentRepository.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(payment));
        lenient().when(paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(10)).thenReturn(List.of(payment));
        return payment;
    }

    @Test
    void paymentSucceededMarksPaidOnceAndIgnoresReplays() {
        Payment payment = stored(PaymentStatus.PENDING, BookingStatus.PENDING);
        receive(new GatewayWebhookEvent("evt_1", "payment_intent.succeeded", "pi_1", null, null, "card"));

        WebhookService.Result first = webhookService.handle("{}", "sig");
        WebhookService.Result replay = webhookService.handle("{}", "sig");

        assertThat(first.action()).isEqualTo(Action.PROCESSED);
        assertThat(replay.action()).isEqualTo(Action.ALREADY_PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaymentMethod()).isEqualTo("card");
        assertThat(payment.getBooking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void paymentFailedMarksFailed() {
        Payment payment = stored(PaymentStatus.PENDING, BookingStatus.PENDING);
        receive(new GatewayWebhookEvent("evt_2", "payment_intent.payment_failed", "pi_1", null, null, null));

        assertThat(webhookService.handle("{}", null).action()).isEqualTo(Action.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void successAfterADeclineOnTheSamePaymentIntentMarksTheFailedPaymentPaid() {
        // Stripe.js: the first card is declined, the customer tries another card on the same PaymentIntent.
        Payment payment = stored(PaymentStatus.PENDING, BookingStatus.PENDING);
        receive(new GatewayWebhookEvent("evt_f", "payment_intent.payment_failed", "pi_1", null, null, null));
        webhookService.handle("{}", "sig");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        receive(new GatewayWebhookEvent("evt_s", "payment_intent.succeeded", "pi_1", null, null, "card"));

        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getBooking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void lateFailureEventDoesNotOverwritePaidPayment() {
        Payment payment = stored(PaymentStatus.PAID, BookingStatus.CONFIRMED);
        receive(new GatewayWebhookEvent("evt_3", "payment_intent.payment_failed", "pi_1", null, null, null));

        assertThat(webhookService.handle("{}", null).action()).isEqualTo(Action.ALREADY_PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void refundWebhookCompletesPendingRefundAndPublishesOnce() {
        Payment payment = stored(PaymentStatus.REFUND_PENDING, BookingStatus.CANCELLED);
        receive(new GatewayWebhookEvent("evt_4", "refund.updated", "pi_1", "re_9", "succeeded", null));

        WebhookService.Result first = webhookService.handle("{}", null);
        WebhookService.Result replay = webhookService.handle("{}", null);

        assertThat(first.action()).isEqualTo(Action.PROCESSED);
        assertThat(replay.action()).isEqualTo(Action.ALREADY_PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_9");
        assertThat(payment.getBooking().getStatus()).isEqualTo(BookingStatus.REFUNDED);
        verify(publisher, times(1)).publishRefundNotification(any());
    }

    @Test
    void pendingRefundWebhookIsIgnored() {
        Payment payment = stored(PaymentStatus.REFUND_PENDING, BookingStatus.CANCELLED);
        receive(new GatewayWebhookEvent("evt_5", "refund.created", "pi_1", "re_9", "pending", null));

        assertThat(webhookService.handle("{}", null).action()).isEqualTo(Action.IGNORED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        verify(publisher, never()).publishRefundNotification(any());
    }

    @Test
    void unknownPaymentIntentIsIgnored() {
        when(paymentRepository.findByStripePaymentIntentId("pi_unknown")).thenReturn(Optional.empty());
        receive(new GatewayWebhookEvent("evt_6", "payment_intent.succeeded", "pi_unknown", null, null, "card"));

        assertThat(webhookService.handle("{}", null).action()).isEqualTo(Action.IGNORED);
    }

    @Test
    void unhandledEventTypeIsIgnored() {
        receive(new GatewayWebhookEvent("evt_7", "customer.created", null, null, null, null));

        assertThat(webhookService.handle("{}", null).action()).isEqualTo(Action.IGNORED);
    }

    @Test
    void invalidSignatureIsRejected() {
        when(gateway.parseWebhook(anyString(), any())).thenThrow(new InvalidWebhookException("Invalid signature"));

        assertThatThrownBy(() -> webhookService.handle("{}", "bad")).isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    void failedRefundWebhookMarksRefundFailedAlertsAdminAndSendsToTheDlq() {
        Payment payment = stored(PaymentStatus.REFUND_PENDING, BookingStatus.CANCELLED);
        receive(new GatewayWebhookEvent("evt_9", "refund.failed", "pi_1", "re_9", "failed", null));

        WebhookService.Result first = webhookService.handle("{}", "sig");
        WebhookService.Result replay = webhookService.handle("{}", "sig");

        assertThat(first.action()).isEqualTo(Action.PROCESSED);
        assertThat(replay.action()).isEqualTo(Action.ALREADY_PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        ArgumentCaptor<RefundFailedEvent> alert = ArgumentCaptor.forClass(RefundFailedEvent.class);
        verify(publisher, times(1)).publishAdminAlert(alert.capture());
        assertThat(alert.getValue().trigger()).isEqualTo("WEBHOOK");
        assertThat(alert.getValue().failureReason()).contains("re_9").contains("failed");
        ArgumentCaptor<FailedRefundMessage> dlq = ArgumentCaptor.forClass(FailedRefundMessage.class);
        verify(publisher, times(1)).publishFailedRefund(dlq.capture());
        assertThat(dlq.getValue().paymentId()).isEqualTo(1);
        verify(publisher, never()).publishRefundNotification(any());
    }

    @Test
    void refundFailedForAPaymentThatIsNotPendingIsIgnored() {
        Payment payment = stored(PaymentStatus.REFUNDED, BookingStatus.REFUNDED);
        receive(new GatewayWebhookEvent("evt_10", "refund.updated", "pi_1", "re_9", "canceled", null));

        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.IGNORED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(publisher, never()).publishAdminAlert(any());
        verify(publisher, never()).publishFailedRefund(any());
    }

    @Test
    void lateSuccessForAGivenUpRefundCompletesIt() {
        Payment payment = stored(PaymentStatus.REFUND_FAILED, BookingStatus.CANCELLED);
        receive(new GatewayWebhookEvent("evt_11", "charge.refund.updated", "pi_1", "re_9", "succeeded", null));

        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(publisher, times(1)).publishRefundNotification(any());
    }

    // ---------------------------------------------------------------- refunds matched by refund id

    private Payment storedWithRefund(PaymentStatus status, String refundId) {
        Payment payment = stored(status, BookingStatus.REFUNDED);
        ReflectionTestUtils.setField(payment, "stripeRefundId", refundId);
        return payment;
    }

    @Test
    void refundThatFailsAfterSucceedingTurnsARefundedPaymentIntoRefundFailed() {
        // Stripe test card pm_card_refundFail: the refund first succeeds, then refund.failed arrives.
        Payment payment = storedWithRefund(PaymentStatus.REFUNDED, "re_9");
        ReflectionTestUtils.setField(payment, "refundedAt", java.time.LocalDateTime.of(2026, 10, 6, 10, 0));
        receive(new GatewayWebhookEvent("evt_20", "refund.failed", "pi_1", "re_9", "failed", null));

        WebhookService.Result first = webhookService.handle("{}", "sig");
        WebhookService.Result replay = webhookService.handle("{}", "sig");

        assertThat(first.action()).isEqualTo(Action.PROCESSED);
        assertThat(replay.action()).isEqualTo(Action.ALREADY_PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(payment.getRefundedAt()).isNull();
        assertThat(payment.getStripeRefundId()).isEqualTo("re_9");
        assertThat(payment.getRefundFailureReason()).contains("re_9");
        ArgumentCaptor<FailedRefundMessage> dlq = ArgumentCaptor.forClass(FailedRefundMessage.class);
        verify(publisher, times(1)).publishAdminAlert(any());
        verify(publisher, times(1)).publishFailedRefund(dlq.capture());
        assertThat(dlq.getValue().trigger()).isEqualTo("WEBHOOK");
        assertThat(dlq.getValue().stripeRefundId()).isEqualTo("re_9");
    }

    @Test
    void outdatedSuccessOfTheRefundThatFailedIsIgnored() {
        Payment payment = storedWithRefund(PaymentStatus.REFUND_FAILED, "re_9");

        receive(new GatewayWebhookEvent("evt_21", "refund.created", "pi_1", "re_9", "succeeded", null));
        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.IGNORED);
        receive(new GatewayWebhookEvent("evt_22", "charge.refunded", "pi_1", null, "succeeded", null));
        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.IGNORED);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        verify(publisher, never()).publishRefundNotification(any());
    }

    @Test
    void successOfAnotherRefundReconcilesAFailedPayment() {
        Payment payment = storedWithRefund(PaymentStatus.REFUND_FAILED, "re_9");
        receive(new GatewayWebhookEvent("evt_23", "refund.updated", "pi_1", "re_10", "succeeded", null));

        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStripeRefundId()).isEqualTo("re_10");
        verify(publisher, times(1)).publishRefundNotification(any());
    }

    @Test
    void pendingRefundIgnoresEventsAboutOtherRefunds() {
        Payment payment = storedWithRefund(PaymentStatus.REFUND_PENDING, "re_9");

        receive(new GatewayWebhookEvent("evt_24", "refund.failed", "pi_1", "re_8", "failed", null));
        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.IGNORED);
        receive(new GatewayWebhookEvent("evt_25", "refund.updated", "pi_1", "re_8", "succeeded", null));
        assertThat(webhookService.handle("{}", "sig").action()).isEqualTo(Action.IGNORED);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        verify(publisher, never()).publishAdminAlert(any());
        verify(publisher, never()).publishRefundNotification(any());
    }
}
