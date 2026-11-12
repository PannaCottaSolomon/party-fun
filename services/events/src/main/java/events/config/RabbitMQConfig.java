package events.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RabbitMQConfig {

    // Spring Boot's auto-configured RabbitAdmin creates it on the broker when the first connection opens.
    @Bean
    public TopicExchange notificationsExchange(
            @Value("${events.rabbitmq.notifications-exchange}") String name) {
        return new TopicExchange(name);
    }

    @Bean
    public TopicExchange partyEventsExchange(
            @Value("${events.rabbitmq.party-events-exchange}") String name) {
        return new TopicExchange(name);
    }

    // Tells Spring RabbitTemplate to automatically convert Java DTOs to JSON messages.
    // Each message gets content_type=application/json and a __TypeId__ header holding the Java class name.
    // NOTE: Jackson2JsonMessageConverter is deprecated for removal in Spring AMQP 4;
    // JacksonJsonMessageConverter is the Jackson 3 replacement.
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
