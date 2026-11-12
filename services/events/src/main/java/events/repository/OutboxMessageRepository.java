package events.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import events.entity.OutboxMessage;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    // SELECT * FROM outbox_messages ORDER BY id LIMIT 100
    // Used by Outbox.relay(): the oldest messages still waiting for the broker
    List<OutboxMessage> findTop100ByOrderByIdAsc();

    // UPDATE outbox_messages SET attempts = attempts + 1, last_error = ? WHERE id = ?
    // Used by Outbox.relay() when the broker did not take a message
    @Modifying
    @Query("UPDATE OutboxMessage m SET m.attempts = m.attempts + 1, m.lastError = :error WHERE m.id = :id")
    int recordFailedAttempt(@Param("id") Long id, @Param("error") String error);
}
