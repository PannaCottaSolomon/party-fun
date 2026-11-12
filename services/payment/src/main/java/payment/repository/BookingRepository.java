package payment.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import payment.entity.Booking;
import payment.entity.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Integer> {

    List<Booking> findAllByOrderByBookingIdDesc();

    List<Booking> findByUserIdOrderByBookingIdDesc(Integer userId);

    List<Booking> findByEventIdAndStatusIn(Integer eventId, Collection<BookingStatus> statuses);

    /**
     * The checkout booking for an Event Service booking id, row-locked (SELECT ... FOR UPDATE) so two concurrent
     * checkouts for the same booking are handled one after the other and can never both charge.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.eventBookingId = :eventBookingId")
    Optional<Booking> findByEventBookingIdForUpdate(@Param("eventBookingId") String eventBookingId);
}
