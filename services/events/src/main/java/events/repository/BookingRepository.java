package events.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import events.entity.Booking;
import events.entity.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Integer> {

    // SELECT EXISTS(... WHERE event_id = ? AND user_id = ? AND status IN (...))
    // Used by EventService.signUp() to reject a second active booking with a clear error message
    boolean existsByEventIdAndUserIdAndStatusIn(Integer eventId, Integer userId,
            Collection<BookingStatus> statuses);

    // SELECT * FROM bookings WHERE event_id = ? AND user_id = ? AND status = ? AND expires_at <= ?
    // Used by EventService.signUp() to find the user's PENDING booking whose payment deadline has passed
    List<Booking> findByEventIdAndUserIdAndStatusAndExpiresAtLessThanEqual(Integer eventId, Integer userId,
            BookingStatus status, LocalDateTime now);

    // SELECT * FROM bookings WHERE user_id = ? ORDER BY created_at DESC, id DESC
    // Used by EventService.listBookingsForUser(): a user's bookings in every status, for My Bookings on any device
    List<Booking> findByUserIdOrderByCreatedAtDescIdDesc(Integer userId);

    // SELECT * FROM bookings WHERE event_id = ? AND status IN (...)
    // Used by EventService to find who to notify when an event changes or is cancelled
    List<Booking> findByEventIdAndStatusIn(Integer eventId, Collection<BookingStatus> statuses);

    // SELECT * FROM bookings WHERE order_id = ? ORDER BY id
    // Used by BookingPaymentService.unpaidOrderOf(): the tickets of one signup, which are paid together
    List<Booking> findByOrderIdOrderByIdAsc(String orderId);

    // UPDATE bookings SET status = 'CONFIRMED', ... WHERE id IN (...) AND status = 'PENDING'
    // Used by BookingFinalizerService.confirm(); returns the number of rows changed
    @Modifying
    @Query("UPDATE Booking b SET b.status = events.entity.BookingStatus.CONFIRMED, b.confirmedAt = :now, "
            + "b.paymentReference = :paymentReference "
            + "WHERE b.id IN :ids AND b.status = events.entity.BookingStatus.PENDING")
    int confirmIfPending(@Param("ids") Collection<Integer> ids, @Param("paymentReference") String paymentReference,
            @Param("now") LocalDateTime now);

    // UPDATE bookings SET failed_at = ?, failure_reason = ? WHERE id = ? AND status = 'PENDING'
    // Used by BookingFinalizerService.recordPaymentFailure(); the booking stays PENDING
    @Modifying
    @Query("UPDATE Booking b SET b.failedAt = :now, b.failureReason = :failureReason "
            + "WHERE b.id = :id AND b.status = events.entity.BookingStatus.PENDING")
    int recordFailureIfPending(@Param("id") Integer id, @Param("failureReason") String failureReason,
            @Param("now") LocalDateTime now);

    // UPDATE bookings SET payment_booking_id = ? WHERE id IN (...)
    // Used by BookingFinalizerService.linkPaymentBooking() once the payment service has created the order's booking
    @Modifying
    @Query("UPDATE Booking b SET b.paymentBookingId = :paymentBookingId WHERE b.id IN :ids")
    int linkPaymentBooking(@Param("ids") Collection<Integer> ids, @Param("paymentBookingId") Integer paymentBookingId);

    // UPDATE bookings SET status = 'CANCELLED' WHERE event_id = ? AND status IN ('PENDING', 'CONFIRMED')
    // Used by EventService.delete() (and so cancel()): the event's places no longer exist
    @Modifying
    @Query("UPDATE Booking b SET b.status = events.entity.BookingStatus.CANCELLED "
            + "WHERE b.eventId = :eventId AND b.status IN "
            + "(events.entity.BookingStatus.PENDING, events.entity.BookingStatus.CONFIRMED)")
    int cancelActiveBookingsForEvent(@Param("eventId") Integer eventId);

    // UPDATE bookings SET payment_reference = ?, refund_required_at = ? WHERE id = ? AND refund_required_at IS NULL
    // Used by BookingFinalizerService.recordRefundOwed() before a compensation refund is attempted
    @Modifying
    @Query("UPDATE Booking b SET b.paymentReference = :paymentReference, b.refundRequiredAt = :now "
            + "WHERE b.id = :id AND b.refundRequiredAt IS NULL")
    int recordRefundOwed(@Param("id") Integer id, @Param("paymentReference") String paymentReference,
            @Param("now") LocalDateTime now);

    // UPDATE bookings SET refunded_at = ?, refund_reference = ? WHERE id = ? AND refunded_at IS NULL
    // Used by BookingFinalizerService.recordRefundSettled() once the payment service has taken the refund over
    @Modifying
    @Query("UPDATE Booking b SET b.refundedAt = :now, b.refundReference = :refundReference "
            + "WHERE b.id = :id AND b.refundedAt IS NULL")
    int recordRefundSettled(@Param("id") Integer id, @Param("refundReference") String refundReference,
            @Param("now") LocalDateTime now);

    // SELECT * FROM bookings WHERE refund_required_at IS NOT NULL AND refunded_at IS NULL
    // Used by BookingRefundService.retryOwedRefunds(): compensation refunds that have not gone through yet
    List<Booking> findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc();

    // PENDING bookings that reached the payment service (payment_booking_id set), were created before createdBefore
    // and whose payment window closed no earlier than expiredAfter.
    // Used by EventService.reconcileUnconfirmedPayments() to find payments whose answer was lost
    @Query("SELECT b FROM Booking b WHERE b.status = events.entity.BookingStatus.PENDING "
            + "AND b.paymentBookingId IS NOT NULL AND b.createdAt <= :createdBefore AND b.expiresAt > :expiredAfter "
            + "ORDER BY b.id")
    List<Booking> findUnconfirmedPayments(@Param("createdBefore") LocalDateTime createdBefore,
            @Param("expiredAfter") LocalDateTime expiredAfter);
}
