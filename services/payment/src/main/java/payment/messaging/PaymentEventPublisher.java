package payment.messaging;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import payment.config.PaymentProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the Payment Service's outgoing RabbitMQ messages. Bodies are plain JSON (no Java type
 * headers) so services written in any language can read them.
 *
 * <ul>
 *   <li>{@code notify.refund} and {@code notify.admin_alert} on the {@code notifications} exchange (best effort).</li>
 *   <li>One {@link FailedRefundMessage} per finally failed refund on {@code payments.refund.dlx}, waiting for the
 *       broker's publisher confirm.</li>
 * </ul>
 */
@Component
public class PaymentEventPublisher {

    /** Header carrying the payment id on DLQ messages, so recovery can match a message without parsing it. */
    public static final String PAYMENT_ID_HEADER = "payment-id";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;
    private final PaymentProperties.Messaging messaging;
    private final boolean adminAlertEnabled;

    public PaymentEventPublisher(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper, PaymentProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
        this.messaging = properties.messaging();
        this.adminAlertEnabled = properties.adminAlert().enabled();
    }

    /**
     * {@code notify.refund} on the {@code notifications} exchange, after Stripe confirmed a refund: the existing
     * Notification Service emails the participant. Sent directly by this service (no Event Service round trip).
     *
     * <p>Best effort: the refund has already succeeded and is never undone because of the email. If the user id or
     * event title is unknown (booking created without an event title) nothing is sent, because the Notification
     * Service would reject the message; if RabbitMQ is unavailable the error is logged.
     *
     * @return true if the message was published
     */
    public boolean publishRefundNotification(RefundNotification refund) {
        if (!refund.hasRequiredFields()) {
            log.warn("Payment {} refunded, but notify.refund was not sent: the booking has no user id "
                    + "and/or event title", refund.paymentId());
            return false;
        }
        try {
            send(messaging.notificationExchange(), messaging.refundNotificationRoutingKey(), refundEmail(refund),
                    refundNotificationId(refund));
            log.info("Published notify.refund for payment {}", refund.paymentId());
            return true;
        } catch (AmqpException e) {
            log.error("Payment {} refunded, but notify.refund could not be published: {}", refund.paymentId(),
                    e.getMessage());
            return false;
        }
    }

    /** Deterministic, so a repeated publish for the same refund never sends a second email. */
    static String refundNotificationId(RefundNotification refund) {
        return "refund-payment-" + refund.paymentId();
    }

    /**
     * Payload in the Notification Service's {@code refund} contract (snake_case, amount in cents). The recipient is
     * the user id; the Notification Service looks up their email address and name.
     */
    static Map<String, Object> refundEmail(RefundNotification refund) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notification_id", refundNotificationId(refund));
        body.put("user_id", refund.userId());
        body.put("event_title", refund.eventTitle());
        body.put("amount", refund.amountCents());
        body.put("currency", refund.currency());
        body.put("registration_id", refund.registrationId());
        if (refund.refundId() != null) {
            body.put("refund_id", refund.refundId());
        }
        return body;
    }

    /**
     * Puts one finally failed refund into {@code payments.refunds.dlq}: published to {@code payments.refund.dlx} with
     * routing key {@code payments.refunds.dlq} (application-managed routing; RabbitMQ does not dead-letter individual
     * refunds by itself). Waits for the broker's publisher confirm, so a lost message surfaces as an exception
     * instead of disappearing.
     *
     * @throws AmqpException if the broker is unavailable or does not confirm the message. The payment is already
     *                       REFUND_FAILED in MySQL, so the Admin can still find and recover it.
     */
    public void publishFailedRefund(FailedRefundMessage failure) {
        MessageProperties properties = jsonProperties(failedRefundMessageId(failure));
        properties.setHeader(PAYMENT_ID_HEADER, failure.paymentId());
        Message message = new Message(jsonMapper.writeValueAsBytes(failure), properties);
        rabbitTemplate.invoke(operations -> {
            operations.send(messaging.refundDeadLetterExchange(), messaging.refundDeadLetterQueue(), message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT.toMillis());
            return null;
        });
        log.error("Refund for payment {} failed; published to {} (routing key {}) and confirmed by the broker",
                failure.paymentId(), messaging.refundDeadLetterExchange(), messaging.refundDeadLetterQueue());
    }

    static String failedRefundMessageId(FailedRefundMessage failure) {
        long at = failure.failedAt() == null ? 0 : failure.failedAt().toEpochMilli();
        return "refund-failed-" + failure.paymentId() + "-" + at;
    }

    /**
     * After the last retry failed: a {@code notify.admin_alert} to the existing Notification Service, which emails
     * the admin (IT Support). Carries the failure reason and attempt count. This is separate from dead-lettering:
     * RabbitMQ's DLQ does not email anyone by itself.
     *
     * <p>Best effort: a broker failure is logged. The failed refund is still preserved in the DLQ (and marked
     * REFUND_FAILED in the database), so nothing is lost if the alert cannot be sent.
     */
    public void publishAdminAlert(RefundFailedEvent event) {
        log.error("Refund for payment {} failed after {} attempts: {}", event.paymentId(), event.retryCount(),
                event.failureReason());
        if (!adminAlertEnabled) {
            return;
        }
        try {
            send(messaging.notificationExchange(), messaging.adminAlertRoutingKey(), adminAlert(event),
                    "refund.failed-" + event.paymentId() + "-" + event.timestamp().toEpochMilli());
        } catch (AmqpException e) {
            log.error("notify.admin_alert for payment {} could not be published: {}", event.paymentId(),
                    e.getMessage());
        }
    }

    /** Payload in the Notification Service's {@code admin_alert} contract (snake_case, cents). */
    static Map<String, Object> adminAlert(RefundFailedEvent event) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("payment_id", event.paymentId());
        details.put("booking_id", event.bookingId());
        details.put("event_id", event.eventId());
        details.put("user_id", event.userId());
        details.put("amount", event.amountCents());
        details.put("currency", event.currency());
        details.put("stripe_payment_intent_id", event.stripePaymentIntentId());
        details.put("reason", event.failureReason());
        details.put("stripe_error_code", event.errorCode());
        details.put("retryable", event.retryable());
        details.put("retries", event.retryCount());
        details.put("trigger", event.trigger());
        details.put("dead_letter_exchange", "payments.refund.dlx");
        details.put("dead_letter_queue", "payments.refunds.dlq");
        details.put("admin_action", "Review and retry on the Admin dashboard (POST /admin/refunds/"
                + event.paymentId() + "/retry)");

        Map<String, Object> alert = new LinkedHashMap<>();
        // Unique per failure, so a later failure of the same payment still alerts the admin.
        alert.put("notification_id",
                "refund-failed-payment-" + event.paymentId() + "-" + event.timestamp().toEpochMilli());
        alert.put("source", "payment-service");
        alert.put("error", "Refund failed after " + event.retryCount() + " attempts for payment " + event.paymentId());
        alert.put("details", details);
        return alert;
    }

    private void send(String exchange, String routingKey, Object payload, String messageId) {
        rabbitTemplate.send(exchange, routingKey,
                new Message(jsonMapper.writeValueAsBytes(payload), jsonProperties(messageId)));
    }

    private static MessageProperties jsonProperties(String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("utf-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(messageId);
        return properties;
    }
}
