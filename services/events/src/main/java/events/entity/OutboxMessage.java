package events.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A message waiting in the transactional outbox until the broker has confirmed it (see messaging/Outbox.java)
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {

    // Primary key (PostgreSQL identity column); also the publish order
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Where it goes and what it says (the JSON body exactly as it is published)
    private String exchange;
    private String routingKey;
    @Column(columnDefinition = "text")
    private String payload;

    private LocalDateTime createdAt;  // when the transaction that produced it committed (or was about to)
    private int attempts;             // failed delivery attempts so far
    private String lastError;         // why the last attempt failed

    // No-args constructor required by JPA
    protected OutboxMessage() {
    }

    public OutboxMessage(String exchange, String routingKey, String payload, LocalDateTime createdAt) {
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getPayload() {
        return payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
