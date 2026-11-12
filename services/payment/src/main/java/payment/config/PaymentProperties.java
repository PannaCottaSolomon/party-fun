package payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code payment.*} in application.yml.
 *
 * @param refund     refund retry settings
 * @param messaging  RabbitMQ exchange, queue and routing-key names
 * @param adminAlert whether a notify.admin_alert is published when a refund finally fails
 * @param recovery   when an abandoned REFUND_PENDING payment is resumed (see {@code RefundRecoveryJob})
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(Refund refund, Messaging messaging, AdminAlert adminAlert, Recovery recovery) {

    public PaymentProperties {
        if (recovery == null) {
            recovery = new Recovery(null, null);
        }
    }

    /**
     * @param maxAttempts total Stripe refund attempts per payment, the first one included (3 = 1 attempt + 2 retries)
     * @param backoffMs   base delay between attempts; attempt n waits n * backoffMs
     */
    public record Refund(int maxAttempts, long backoffMs) {
    }

    /**
     * @param exchange                     {@code party.events} (topic)
     * @param eventCancelledRoutingKey     key on {@code exchange} published by the Event Service when an event is
     *                                     cancelled; bound to {@code refundRequestedQueue}
     * @param refundRequestedQueue         {@code payments.refunds.requested}
     * @param refundDeadLetterExchange     {@code payments.refund.dlx} (direct): Payment publishes each finally failed
     *                                     refund here itself
     * @param refundDeadLetterQueue        {@code payments.refunds.dlq}, also the routing key into it
     * @param notificationExchange         the Notification Service's {@code notifications} exchange (topic)
     * @param refundNotificationRoutingKey key on {@code notificationExchange} for the participant refund email
     * @param adminAlertRoutingKey         key on {@code notificationExchange} for the IT Support / Admin alert
     * @param managementUrl                RabbitMQ management HTTP API, only for the DLQ ready/unacknowledged counts
     */
    public record Messaging(
            String exchange,
            String eventCancelledRoutingKey,
            String refundRequestedQueue,
            String refundDeadLetterExchange,
            String refundDeadLetterQueue,
            String notificationExchange,
            String refundNotificationRoutingKey,
            String adminAlertRoutingKey,
            String managementUrl) {
    }

    public record AdminAlert(boolean enabled) {
    }

    /**
     * @param staleAfter a REFUND_PENDING payment without a Stripe refund that hasn't changed for this long is resumed
     * @param interval   how often the recovery job looks for such payments
     */
    public record Recovery(Duration staleAfter, Duration interval) {

        public Recovery {
            staleAfter = staleAfter == null ? Duration.ofMinutes(10) : staleAfter;
            interval = interval == null ? Duration.ofMinutes(5) : interval;
        }
    }
}
