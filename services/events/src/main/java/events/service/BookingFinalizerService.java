package events.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import events.entity.Booking;
import events.entity.BookingStatus;
import events.repository.BookingRepository;

/*  Signup transaction 2 (and later): record payment outcomes on the bookings of an order (one signup's tickets).

    Each payment method is a conditional UPDATE (... WHERE status = 'PENDING'), so it needs no row lock and can
    never overwrite a booking that was expired or cancelled while the payment was in flight. On success the passed
    Bookings are updated too, so the caller can return them without reloading them. */
@Service
public class BookingFinalizerService {

    // bookings.failure_reason and bookings.refund_reference are varchar(255)
    private static final int MAX_FAILURE_REASON_LENGTH = 255;

    private final BookingRepository bookingRepository;
    private final Clock clock;

    // Production constructor: uses the UTC system clock
    @Autowired
    public BookingFinalizerService(BookingRepository bookingRepository) {
        this(bookingRepository, Clock.systemUTC());
    }

    // Full constructor: lets unit tests inject a fixed Clock
    public BookingFinalizerService(BookingRepository bookingRepository, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    // Remembers which payment-service booking belongs to these bookings (one order), so a payment retry reuses it
    @Transactional
    public void linkPaymentBooking(List<Booking> order, Integer paymentBookingId) {
        bookingRepository.linkPaymentBooking(ids(order), paymentBookingId);
        order.forEach(booking -> booking.setPaymentBookingId(paymentBookingId));
    }

    // PENDING -> CONFIRMED for every booking of an order, or for none: if any of them is no longer PENDING, nothing
    // changes and this returns false. paymentReference is null for a free event.
    @Transactional
    public boolean confirm(List<Booking> order, String paymentReference) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (bookingRepository.confirmIfPending(ids(order), paymentReference, now) != order.size()) {
            // Undo the bookings that were confirmed: an order is paid, and confirmed, as a whole
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return false;
        }
        for (Booking booking : order) {
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setConfirmedAt(now);
            booking.setPaymentReference(paymentReference);
        }
        return true;
    }

    // Payment declined: the bookings stay PENDING (places held until expiresAt) so the user can retry
    @Transactional
    public void recordPaymentFailure(List<Booking> order, String failureReason) {
        LocalDateTime now = LocalDateTime.now(clock);
        String reason = truncate(failureReason);
        for (Booking booking : order) {
            if (bookingRepository.recordFailureIfPending(booking.getId(), reason, now) == 1) {
                booking.setFailedAt(now);
                booking.setFailureReason(reason);
            }
        }
    }

    // The card was charged but the booking no longer holds a place: record the refund as owed before trying it,
    // so it survives a payment service outage or a restart (BookingRefundService retries it until it goes through)
    @Transactional
    public void recordRefundOwed(Booking booking, Integer paymentId) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (bookingRepository.recordRefundOwed(booking.getId(), paymentId.toString(), now) == 1) {
            booking.setPaymentReference(paymentId.toString());
            booking.setRefundRequiredAt(now);
        }
    }

    // The payment service has taken the owed refund over (refunded, or tracked by its own refund recovery)
    @Transactional
    public void recordRefundSettled(Booking booking, String refundReference) {
        LocalDateTime now = LocalDateTime.now(clock);
        String reference = truncate(refundReference);
        if (bookingRepository.recordRefundSettled(booking.getId(), reference, now) == 1) {
            booking.setRefundedAt(now);
            booking.setRefundReference(reference);
        }
    }

    private static List<Integer> ids(List<Booking> bookings) {
        return bookings.stream().map(Booking::getId).toList();
    }

    private static String truncate(String reason) {
        return reason != null && reason.length() > MAX_FAILURE_REASON_LENGTH
                ? reason.substring(0, MAX_FAILURE_REASON_LENGTH)
                : reason;
    }
}
