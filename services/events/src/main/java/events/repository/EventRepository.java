package events.repository;

import events.entity.Event;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    // Pessimistic write lock: SELECT ... FROM events WHERE id = ? FOR UPDATE
    // The row stays locked until the caller's transaction commits or rolls back, so concurrent signups,
    // updates and deletes of the same event run one at a time. Must be called inside a @Transactional method.
    // The lock timeout (ms) stops a request waiting forever; a timeout surfaces as a
    // PessimisticLockingFailureException, which EventExceptionHandler turns into 503 (safe to try again).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

    List<Event> findByExpiresAtLessThanEqualAndStatus(LocalDateTime expiresAt, String status);
}
