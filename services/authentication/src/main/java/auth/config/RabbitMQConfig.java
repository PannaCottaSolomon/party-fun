package auth.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Choreography-style messaging setup. This service publishes user lifecycle
 * events ("user.created", "user.updated", "user.deleted") onto the
 * "user.events" topic exchange; any service can bind a queue to the routing
 * keys it cares about. This service does not know or care who is listening.
 *
 * <p>Before the user-service merge, registration published "auth.registered" on
 * "auth.events" so user-service could create the profile. Registration now
 * writes the whole account in one transaction and announces "user.created".
 */
@Configuration
public class RabbitMQConfig {

    public static final String USER_EXCHANGE = "user.events";

    @Bean
    public TopicExchange userExchange() {
        return new TopicExchange(USER_EXCHANGE, true, false);
    }

    // Jackson2JsonMessageConverter (Jackson 2) is deprecated for removal in Spring AMQP 4;
    // JacksonJsonMessageConverter is the Jackson 3 replacement that Spring Boot 4 ships.
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
