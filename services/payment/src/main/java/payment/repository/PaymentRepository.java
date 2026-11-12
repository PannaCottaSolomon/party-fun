package payment.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import payment.entity.Payment;
import payment.entity.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, Integer> {

    List<Payment> findAllByOrderByPaymentIdDesc();

    List<Payment> findByBookingBookingIdOrderByPaymentIdAsc(Integer bookingId);

    boolean existsByBookingBookingIdAndStatusIn(Integer bookingId, Collection<PaymentStatus> statuses);

    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

    /** Payments of a user, via this service's own booking table (never the User Service database). */
    @Query("select p from Payment p join p.booking b where b.userId = :userId order by p.paymentId desc")
    List<Payment> findByUserId(@Param("userId") Integer userId);

    /** Payments for an event, via booking.event_id (never the Event Service database). */
    @Query("select p from Payment p join p.booking b where b.eventId = :eventId and p.status in :statuses"
            + " order by p.paymentId")
    List<Payment> findByEventIdAndStatusIn(@Param("eventId") Integer eventId,
                                           @Param("statuses") Collection<PaymentStatus> statuses);

    /** Individually failed refunds for the Admin dashboard, oldest failure first. */
    @Query("select p from Payment p join fetch p.booking where p.status = :status"
            + " order by p.refundFailedAt asc, p.paymentId asc")
    List<Payment> findWithBookingByStatus(@Param("status") PaymentStatus status);

    /**
     * Refunds that were claimed (REFUND_PENDING) but never reached Stripe and haven't changed since {@code cutoff}:
     * abandoned when the service stopped mid-refund. A REFUND_PENDING payment that has a Stripe refund id is
     * waiting for Stripe's webhook instead and is not returned.
     */
    @Query("select p.paymentId from Payment p where p.status = :status and p.stripeRefundId is null"
            + " and p.updatedAt < :cutoff order by p.paymentId")
    List<Integer> findAbandonedRefunds(@Param("status") PaymentStatus status, @Param("cutoff") LocalDateTime cutoff);

    /** Row lock (SELECT ... FOR UPDATE) so two refund requests cannot claim the same payment at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Integer paymentId);
}
