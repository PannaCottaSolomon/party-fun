package payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import payment.TestData;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Mock
    private RabbitTemplate rabbitTemplate;

    private final JsonMapper json = JsonMapper.builder().build();

    private PaymentEventPublisher publisher(boolean adminAlert) {
        return new PaymentEventPublisher(rabbitTemplate, json, TestData.properties(3, adminAlert));
    }

    private static RefundFailedEvent failedEvent() {
        return new RefundFailedEvent("refund.failed", 1, 10, 42, 7, new BigDecimal("40.00"), 4000, "SGD", "pi_1",
                null, "Stripe refund request failed", 3, "rate_limit", true, "EVENT_CANCELLED", "cancel-42", NOW);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Message message) {
        return json.readValue(message.getBody(), Map.class);
    }

    private static RefundNotification refund(Integer userId, String title) {
        return new RefundNotification(5, "evb-77", userId, title, 4000, "SGD", "re_1");
    }

    @Test
    void refundNotificationGoesToTheNotificationsExchangeInTheNotifyRefundContract() {
        boolean sent = publisher(true).publishRefundNotification(refund(42, "Neon Party"));

        assertThat(sent).isTrue();
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(org.mockito.ArgumentMatchers.eq("notifications"),
                org.mockito.ArgumentMatchers.eq("notify.refund"), message.capture());
        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class));

        // Required: user_id (instead of an email address), event_title, amount (cents), registration_id.
        // Optional: currency, refund_id.
        Map<String, Object> body = body(message.getValue());
        assertThat(body).containsOnlyKeys("notification_id", "user_id", "event_title", "amount", "currency",
                "registration_id", "refund_id");
        assertThat(body).containsEntry("notification_id", "refund-payment-5")
                .containsEntry("user_id", 42)
                .containsEntry("event_title", "Neon Party").containsEntry("amount", 4000)
                .containsEntry("currency", "SGD").containsEntry("registration_id", "evb-77")
                .containsEntry("refund_id", "re_1");
        assertThat(message.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");
        assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo("refund-payment-5");
    }

    @Test
    void refundNotificationIsNotSentWithoutUserOrEventTitle() {
        assertThat(publisher(true).publishRefundNotification(refund(null, "Neon Party"))).isFalse();
        assertThat(publisher(true).publishRefundNotification(refund(42, " "))).isFalse();

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void refundNotificationBrokerFailureIsLoggedNotThrown() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("down")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        assertThat(publisher(true).publishRefundNotification(refund(42, "Neon Party"))).isFalse();
    }

    @Test
    void adminAlertCarriesTheFailureDetails() {
        publisher(true).publishAdminAlert(failedEvent());

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(org.mockito.ArgumentMatchers.eq("notifications"),
                org.mockito.ArgumentMatchers.eq("notify.admin_alert"), message.capture());
        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class));

        Map<String, Object> alert = body(message.getValue());
        assertThat(alert).containsEntry("source", "payment-service");
        assertThat((String) alert.get("error")).contains("3 attempts");
        assertThat((String) alert.get("notification_id")).startsWith("refund-failed-payment-1-");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) alert.get("details");
        assertThat(details).containsEntry("payment_id", 1).containsEntry("amount", 4000)
                .containsEntry("reason", "Stripe refund request failed").containsEntry("retries", 3)
                .containsEntry("stripe_error_code", "rate_limit").containsEntry("retryable", true)
                .containsEntry("trigger", "EVENT_CANCELLED")
                .containsEntry("dead_letter_exchange", "payments.refund.dlx")
                .containsEntry("dead_letter_queue", "payments.refunds.dlq");
    }

    @Test
    void adminAlertNeverContainsSecretsOrCardData() {
        publisher(true).publishAdminAlert(failedEvent());

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), message.capture());
        String raw = new String(message.getValue().getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("sk_test", "sk_live", "whsec_", "client_secret", "card_number", "cvc");
    }

    @Test
    void adminAlertBrokerFailureIsLoggedNotThrown() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("down")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        publisher(true).publishAdminAlert(failedEvent()); // does not throw: the DLQ still preserves the refund
    }

    @Test
    void adminAlertCanBeDisabled() {
        publisher(false).publishAdminAlert(failedEvent());

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    // ---------------------------------------------------------------- failed refunds → payments.refunds.dlq

    @Mock
    private RabbitOperations operations;

    @SuppressWarnings("unchecked")
    private void brokerConfirms() {
        when(rabbitTemplate.invoke(any(RabbitOperations.OperationsCallback.class))).thenAnswer(invocation ->
                ((RabbitOperations.OperationsCallback<Object>) invocation.getArgument(0)).doInRabbit(operations));
    }

    @Test
    void failedRefundIsPublishedIndividuallyToTheDeadLetterExchangeAndConfirmed() {
        brokerConfirms();

        publisher(true).publishFailedRefund(FailedRefundMessage.from(failedEvent()));

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(operations).send(eq("payments.refund.dlx"), eq("payments.refunds.dlq"), message.capture());
        verify(operations).waitForConfirmsOrDie(10_000L);
        Map<String, Object> body = body(message.getValue());
        assertThat(body).containsEntry("paymentId", 1).containsEntry("bookingId", 10).containsEntry("eventId", 42)
                .containsEntry("refundAmountCents", 4000).containsEntry("retryCount", 3)
                .containsEntry("failureReason", "Stripe refund request failed")
                .containsEntry("trigger", "EVENT_CANCELLED").containsEntry("failedAt", "2026-10-06T10:00:00Z");
        assertThat(message.getValue().getMessageProperties().getHeaders())
                .containsEntry(PaymentEventPublisher.PAYMENT_ID_HEADER, 1).doesNotContainKey("__TypeId__");
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getMessageId())
                .isEqualTo("refund-failed-1-" + NOW.toEpochMilli());
    }

    @Test
    void unconfirmedFailedRefundSurfacesAsAnError() {
        brokerConfirms();
        doThrow(new AmqpException("nack")).when(operations).waitForConfirmsOrDie(anyLong());

        assertThatThrownBy(() -> publisher(true).publishFailedRefund(FailedRefundMessage.from(failedEvent())))
                .isInstanceOf(AmqpException.class);
    }

    @Test
    void oldDlqMessagesAreStillReadable() {
        FailedRefundMessage old = json.readValue("""
                {"eventType":"refund.requested","paymentId":7,"bookingId":3,"amountCents":2500,
                 "trigger":"MANUAL","requestedAt":"2026-10-01T00:00:00Z"}""", FailedRefundMessage.class);

        assertThat(old.paymentId()).isEqualTo(7);
        assertThat(old.refundAmountCents()).isEqualTo(2500);
        assertThat(old.failedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void successfulRefundNeverTouchesTheDlq() {
        publisher(true).publishRefundNotification(refund(42, "Neon Party"));

        verify(rabbitTemplate, never()).send(anyString(), eq("payments.refunds.dlq"), any(Message.class));
        verify(rabbitTemplate, never()).invoke(any(RabbitOperations.OperationsCallback.class));
    }
}
