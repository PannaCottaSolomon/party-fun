package events.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import events.entity.Booking;
import events.entity.BookingStatus;
import events.entity.Event;
import events.exception.DuplicateBookingException;
import events.exception.EventFullException;
import events.exception.EventNotFoundException;
import events.exception.EventNotOpenException;
import events.exception.InvalidRequestException;
import events.repository.BookingRepository;
import events.repository.EventRepository;

/*  Signup transaction 1: reserve one place per ticket and create a PENDING booking for each, all in one order.

    This is a separate bean (not a method on EventService) so the call from EventService.signUp() goes through
    the Spring proxy and @Transactional applies. The transaction, and with it the event row lock, ends as soon as
    reserve() returns, before the payment service is called. */
@Service
public class BookingReservationService {

    // How long a PENDING booking holds a place while waiting for payment
    private static final int BOOKING_TIMEOUT_MINUTES = 15;

    // Most tickets (one booking each) a single signup request may buy
    public static final int MAX_TICKETS_PER_SIGNUP = 10;

    // Booking states that hold a place; a user may only have one of these per event.
    // PENDING includes bookings whose payment failed but whose expiresAt has not passed yet.
    private static final EnumSet<BookingStatus> ACTIVE_BOOKING_STATUSES = EnumSet.of(
            BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private final EventRepository eventRepository;      // JPA access to the events table (incl. row locks)
    private final BookingRepository bookingRepository;  // JPA access to the bookings table
    private final Clock clock;                          // time source (fixed Clock in tests)

    // Production constructor: uses the UTC system clock
    @Autowired
    public BookingReservationService(EventRepository eventRepository, BookingRepository bookingRepository) {
        this(eventRepository, bookingRepository, Clock.systemUTC());
    }

    // Full constructor: lets unit tests inject a fixed Clock for predictable timestamps
    public BookingReservationService(EventRepository eventRepository, BookingRepository bookingRepository,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    // Retries up to 3 times with exponential backoff if lock fails or deadlocks occur
    @Retryable(
        retryFor = { CannotAcquireLockException.class, PessimisticLockingFailureException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2)
    )
    @Transactional
    public List<Booking> reserve(Integer eventId, Integer userId, String paymentMethodId, Integer quantity) {
        // 1. Basic request checks
        if (userId == null) {
            throw new InvalidRequestException("A user id is required to sign up for an event");
        }
        int tickets = quantity == null ? 1 : quantity;
        if (tickets < 1 || tickets > MAX_TICKETS_PER_SIGNUP) {
            throw new InvalidRequestException(
                    "quantity must be between 1 and " + MAX_TICKETS_PER_SIGNUP + " tickets per signup");
        }

        // 2. Lock the event row (SELECT ... FOR UPDATE). A concurrent signup for this event BLOCKS here
        // until this transaction commits, then reads the updated capacity. The event must be open.
        Event event = eventRepository.findByIdForUpdate(eventId.longValue())
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (!"ACTIVE".equalsIgnoreCase(event.getStatus())) {
            throw new EventNotOpenException(eventId);
        }

        // A paid event can only be booked with a payment method, so no place is held for a signup that cannot
        // pay. Checked against the locked row, so a concurrent price change cannot slip past it.
        if (BookingPaymentService.requiresPayment(event.getTicketPrice())
                && (paymentMethodId == null || paymentMethodId.isBlank())) {
            throw new InvalidRequestException("A paymentMethodId is required to sign up for a paid event");
        }

        // 3. A failed payment keeps the booking PENDING (and its place held) until expiresAt so the user can
        // retry. If this user's PENDING booking has passed its deadline, expire it and give its place back,
        // so it no longer blocks a fresh signup.
        LocalDateTime now = LocalDateTime.now(clock);
        for (Booking lapsed : bookingRepository.findByEventIdAndUserIdAndStatusAndExpiresAtLessThanEqual(
                eventId, userId, BookingStatus.PENDING, now)) {
            lapsed.setStatus(BookingStatus.EXPIRED);
            event.setCapacity(event.getCapacity() + 1);
        }

        // 4. Evaluate the rules against the latest data (race-free: we hold the row lock).
        // A user signs up once per event (that one signup may hold several tickets). A PENDING booking still
        // within its deadline counts, even after a failed payment: retry the payment.
        if (bookingRepository.existsByEventIdAndUserIdAndStatusIn(eventId, userId,
                ACTIVE_BOOKING_STATUSES)) {
            throw new DuplicateBookingException(eventId, userId);
        }
        // All or nothing: either every requested ticket gets a place or none does
        if (event.getCapacity() < tickets) {
            throw new EventFullException(eventId, tickets, event.getCapacity());
        }

        // 5. Mutate state: take one place per ticket (written by dirty checking on commit)
        event.setCapacity(event.getCapacity() - tickets);

        // 6. Save one PENDING booking per ticket, all in one order (paid by one charge) with the same 15-minute
        // payment deadline
        String orderId = UUID.randomUUID().toString();
        List<Booking> bookings = new ArrayList<>(tickets);
        for (int i = 0; i < tickets; i++) {
            bookings.add(bookingRepository.save(newPendingBooking(event, userId, orderId, now)));
        }
        return bookings;
    } // <-- commits here: the row lock is released before payment starts

    // A PENDING booking at the event's current price, holding the place until now + 15 minutes
    private Booking newPendingBooking(Event event, Integer userId, String orderId, LocalDateTime now) {
        Booking booking = new Booking();
        booking.setEventId(event.getId().intValue());
        booking.setUserId(userId);
        booking.setOrderId(orderId);
        booking.setTotalAmount(event.getTicketPrice());
        booking.setCurrency(event.getCurrency());
        booking.setStatus(BookingStatus.PENDING);
        booking.setCreatedAt(now);
        booking.setExpiresAt(now.plusMinutes(BOOKING_TIMEOUT_MINUTES));
        return booking;
    }
}
