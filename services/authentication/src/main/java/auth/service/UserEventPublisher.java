package auth.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import auth.config.RabbitMQConfig;
import auth.entity.UserAccount;

@Component
public class UserEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);
    private final RabbitTemplate rabbitTemplate;

    public UserEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishUserCreated(UserAccount user) {
        publish("user.created", payload(user));
    }

    public void publishUserUpdated(UserAccount user) {
        publish("user.updated", payload(user));
    }

    public void publishUserDeleted(UUID userId) {
        publish("user.deleted", Map.of("id", userId.toString()));
    }

    /** The public view of an account. Intentionally carries no password material. */
    private Map<String, Object> payload(UserAccount user) {
        // HashMap, not Map.of: phoneNumber is optional and Map.of rejects nulls.
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", user.getId().toString());
        payload.put("email", user.getEmail());
        payload.put("fullName", user.getFullName());
        payload.put("role", user.getRole().name());
        payload.put("phoneNumber", user.getPhoneNumber());
        return payload;
    }

    private void publish(String routingKey, Object payload) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.USER_EXCHANGE, routingKey, payload);
        } catch (AmqpException e) {
            // In a demo/course setting a failed publish shouldn't blow up the request
            // that triggered it, but DO log it loudly — a real system would use an
            // outbox pattern here instead.
            log.error("Failed to publish event '{}': {}", routingKey, e.getMessage(), e);
        }
    }
}
