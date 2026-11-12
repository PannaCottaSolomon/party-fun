package events.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import events.client.PaymentUnavailableException;
import events.dto.EventDto;
import events.entity.Booking;
import events.entity.BookingStatus;
import events.entity.Event;
import events.exception.BookingNotFoundException;
import events.exception.BookingNotPayableException;
import events.exception.EventNotFoundException;
import events.exception.InvalidRequestException;
import events.messaging.EventMessaging;
import events.repository.BookingRepository;
import events.repository.EventRepository;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    // The payment service charges in SGD only, with at most 8 digits before and 2 after the decimal point
    private static final String CURRENCY = "SGD";
    private static final BigDecimal MAX_TICKET_PRICE = new BigDecimal("99999999.99");

    // Dependencies
    private final EventRepository eventRepository;                  // JPA access to the events table (incl. row locks)
    private final BookingRepository bookingRepository;              // JPA access to the bookings table
    private final BookingReservationService reservationService;     // signup transaction 1: reserve a place
    private final BookingPaymentService paymentService;             // signup step 2: charge via the payment service
    private final EventMessaging eventMessaging;                    // publishes messages to RabbitMQ
    private final Clock clock;                                      // time source (fixed Clock in tests)

    // Constructors

    // Production constructor: uses the UTC system clock.
    // @Autowired tells Spring which of the two constructors to use (it cannot choose on its own).
    @Autowired
    public EventService(EventRepository eventRepository, BookingRepository bookingRepository,
            BookingReservationService reservationService, BookingPaymentService paymentService,
            EventMessaging eventMessaging) {
        this(eventRepository, bookingRepository, reservationService, paymentService, eventMessaging,
                Clock.systemUTC());
    }

    // Full constructor: lets unit tests inject a fixed Clock for predictable timestamps
    public EventService(EventRepository eventRepository, BookingRepository bookingRepository,
            BookingReservationService reservationService, BookingPaymentService paymentService,
            EventMessaging eventMessaging, Clock clock) {
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
        this.reservationService = reservationService;
        this.paymentService = paymentService;
        this.eventMessaging = eventMessaging;
        this.clock = clock;
    }

    // Create: default the status to ACTIVE and the currency to SGD, validate, save, then publish EVENT_CREATED
    // (a new row cannot be contended, so no lock is needed)
    @Transactional(rollbackFor = Exception.class)
    public EventDto create(EventDto request) {
        Event event = request.toEntity();
        event.setStatus(defaultStatus(request.getStatus()));
        event.setCurrency(normaliseCurrency(request.getCurrency()));
        if (event.getMaxCapacity() == null) {
            event.setMaxCapacity(event.getCapacity());
        }
        validate(event, 1);

        LocalDateTime now = LocalDateTime.now(clock);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        Event savedEvent = eventRepository.save(event);
        eventMessaging.publishEventCreated(savedEvent);
        return EventDto.from(savedEvent);
    }

    // Expired events are confirmed when enough signups were received; otherwise they are cancelled.
    @Transactional(rollbackFor = Exception.class)
    public void processExpiredEvents() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Event> expiredEvents = eventRepository.findByExpiresAtLessThanEqualAndStatus(now, "ACTIVE");

        for (Event event : expiredEvents) {
            int currentSignUps = event.getMaxCapacity() - event.getCapacity();
            if (event.getMinSignUps() != null && currentSignUps < event.getMinSignUps()) {
                cancel(event.getId().intValue());
            }
        }
    }

    // Read: list all events (read-only transaction, no lock)
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<EventDto> listAll() {
        return eventRepository.findAll().stream().map(EventDto::from).toList();
    }

    // Read: one event by id (no lock); throws EventNotFoundException if it does not exist
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public EventDto get(Integer id) {
        return EventDto.from(loadEvent(id));
    }

    // Update: lock the event row, apply non-null fields, re-validate, then publish EVENT_UPDATED (to everyone with a
    // place and the organiser, listing what participants can see that changed)
    // The row lock makes concurrent signups wait, so the update never writes back a stale capacity.
    // The event is managed by JPA, so dirty checking writes the changes when the transaction commits.
    @Transactional(rollbackFor = Exception.class)
    public EventDto update(Integer id, EventDto request) {
        Event event = loadEventForUpdate(id);
        Map<String, String> before = EventMessaging.participantFacingDetails(event);
        applyUpdates(event, request);
        event.setUpdatedAt(LocalDateTime.now(clock));
        Map<String, String> changes =
                EventMessaging.changedDetails(before, EventMessaging.participantFacingDetails(event));
        eventMessaging.publishEventUpdated(event, changes, participantsOf(id));
        return EventDto.from(event);
    }

    // Cancel: lock the event row, run the normal delete, then publish event.cancelled so the payment service
    // cancels its bookings and handles refunds on its side. This is a one-way request; no refund confirmation is
    // consumed by the event service. Both messages are sent only once the delete commits, in order: EVENT_DELETED
    // first, then event.cancelled.
    // A rollback sends neither.
    @Transactional(rollbackFor = Exception.class)
    public EventDto cancel(Integer id) {
        Event event = loadEventForUpdate(id);
        EventDto deletedEvent = deleteLoadedEvent(event, id);
        eventMessaging.publishEventCancelled(event);
        return deletedEvent;
    }

    @Transactional(rollbackFor = Exception.class)
    public EventDto delete(Integer id) {
        Event event = loadEventForUpdate(id);
        return deleteLoadedEvent(event, id);
    }

    private EventDto deleteLoadedEvent(Event event, Integer id) {
        List<Integer> participants = participantsOf(id);  // before their bookings are cancelled
        int cancelled = bookingRepository.cancelActiveBookingsForEvent(id);
        eventRepository.delete(event);
        eventMessaging.publishEventDeleted(event, participants);
        log.info("Event {} deleted, {} active booking(s) cancelled", id, cancelled);
        return EventDto.from(event);
    }

    // Signup: reserve `quantity` places (one booking per ticket, default 1, all in one order), then charge the order
    // once for every ticket. Returns the bookings: all CONFIRMED if paid (or free), otherwise all PENDING with their
    // places held until expiresAt, so the user can retry the payment with payForBooking().
    public List<Booking> signUp(Integer eventId, Integer userId, String paymentMethodId, Integer quantity) {
        // 1. Transaction 1: lock the event, take the places, save the PENDING bookings. Commits (and releases the
        // lock) on return. Business errors (not found, not enough places, duplicate, no payment method, bad
        // quantity) are thrown from here before any payment, so either every ticket is reserved or none is.
        List<Booking> bookings = reservationService.reserve(eventId, userId, paymentMethodId, quantity);

        // 2. Charge the order through the payment service, outside any transaction: one charge for all its tickets.
        // The places are already ours, so a payment service outage does not fail the signup: the bookings stay
        // PENDING and can be paid later.
        boolean confirmed;
        try {
            confirmed = paymentService.pay(bookings, paymentMethodId);
        } catch (PaymentUnavailableException exception) {
            log.warn("Payment for booking(s) {} did not complete, left PENDING: {}", ids(bookings),
                    exception.getMessage());
            confirmed = false;
        }

        // 3. Email the participant once the order is CONFIRMED (a PENDING order sends nothing)
        publishSignupOutcome(bookings, confirmed);
        return bookings;
    }

    // Payment retry for a PENDING booking (e.g. after a declined card), before its payment window closes. The charge
    // covers every unpaid ticket of the booking's order, so retrying any one of them pays for all. A booking already
    // CONFIRMED (e.g. paid together with another ticket of its order) is returned as it is: nothing is charged.
    // Unlike signup, a payment service outage fails this request (503): there is nothing new to keep.
    public Booking payForBooking(Integer eventId, Integer bookingId, String paymentMethodId) {
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw new InvalidRequestException("A paymentMethodId is required to pay for a booking");
        }
        Booking booking = bookingRepository.findById(bookingId)
                .filter(found -> found.getEventId().equals(eventId))
                .orElseThrow(() -> new BookingNotFoundException(eventId, bookingId));
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return booking;
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BookingNotPayableException(bookingId, "it is " + booking.getStatus());
        }
        if (!booking.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new BookingNotPayableException(bookingId,
                    "its payment window closed at " + booking.getExpiresAt());
        }

        List<Booking> order = paymentService.unpaidOrderOf(booking);
        boolean confirmed = paymentService.pay(order, paymentMethodId);
        publishSignupOutcome(order, confirmed);
        return booking;
    }

    // A user's bookings in every status, newest first (read-only, no lock). Bookings of deleted events are kept
    // (CANCELLED), so the user still sees them.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<Booking> listBookingsForUser(Integer userId) {
        if (userId == null) {
            throw new InvalidRequestException("A userId is required to list bookings");
        }
        return bookingRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    // Private helpers: messaging, loading and validation

    private void publishSignupOutcome(List<Booking> order, boolean confirmed) {
        if (confirmed) {
            eventMessaging.publishSignupConfirmation(order);
        }
        // Not confirmed: still PENDING (the user can retry the payment in the app), or it expired or was cancelled
        // while the payment was in flight and was refunded. Neither is a "You're in!" email.
    }

    private static List<Integer> ids(List<Booking> bookings) {
        return bookings.stream().map(Booking::getId).toList();
    }

    // User ids of everyone holding a place (PENDING or CONFIRMED) at an event, each once
    private List<Integer> participantsOf(Integer eventId) {
        List<BookingStatus> active = List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);
        return bookingRepository.findByEventIdAndStatusIn(eventId, active).stream()
                .map(Booking::getUserId).distinct().toList();
    }

    // Finds an event by id or throws "Event {id} not found"
    private Event loadEvent(Integer id) {
        return eventRepository.findById(id.longValue())
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    // Same as loadEvent, but takes a pessimistic write lock on the row (held until the transaction ends)
    private Event loadEventForUpdate(Integer id) {
        return eventRepository.findByIdForUpdate(id.longValue())
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    // PATCH semantics: copy only the fields the client actually sent (non-null), then re-validate
    private void applyUpdates(Event event, EventDto request) {
        if (request.getTitle() != null) {
            event.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            event.setDescription(request.getDescription());
        }
        if (request.getOrganiserId() != null) {
            event.setOrganiserId(request.getOrganiserId());
        }
        if (request.getLocation() != null) {
            event.setLocation(request.getLocation());
        }
        if (request.getStartDateTime() != null) {
            event.setStartDateTime(request.getStartDateTime());
        }
        if (request.getEndDateTime() != null) {
            event.setEndDateTime(request.getEndDateTime());
        }
        if (request.getCapacity() != null) {
            event.setCapacity(request.getCapacity());
        }
        if (request.getMaxCapacity() != null) {
            event.setMaxCapacity(request.getMaxCapacity());
        }
        if (request.getMinSignUps() != null) {
            event.setMinSignUps(request.getMinSignUps());
        }
        if (request.getExpiresAt() != null) {
            event.setExpiresAt(request.getExpiresAt());
        }
        if (request.getTicketPrice() != null) {
            event.setTicketPrice(request.getTicketPrice());
        }
        if (request.getCurrency() != null) {
            event.setCurrency(normaliseCurrency(request.getCurrency()));
        }
        if (request.getStatus() != null) {
            event.setStatus(request.getStatus());
        }
        validate(event, 0);
    }

    // Rules checked on create and after every update: a title, end after start, enough capacity, and a ticket
    // price the payment service can charge.
    // capacity is the number of places left, so a new event needs at least 1 but a sold-out event has 0.
    private void validate(Event event, int minimumCapacity) {
        if (event.getTitle() == null || event.getTitle().isBlank()) {
            throw new InvalidRequestException("Event title is required");
        }
        if (event.getStartDateTime() != null && event.getEndDateTime() != null
                && !event.getEndDateTime().isAfter(event.getStartDateTime())) {
            throw new InvalidRequestException("Event end time must be after its start time");
        }
        if (event.getCapacity() == null || event.getCapacity() < minimumCapacity) {
            throw new InvalidRequestException("Event capacity must be at least " + minimumCapacity);
        }
        BigDecimal price = event.getTicketPrice();
        if (price != null && (price.signum() < 0 || price.stripTrailingZeros().scale() > 2
                || price.compareTo(MAX_TICKET_PRICE) > 0)) {
            throw new InvalidRequestException(
                    "Ticket price must be between 0 and " + MAX_TICKET_PRICE + " with at most 2 decimal places");
        }
    }

    // New events are open for signups unless the client chose another status
    private String defaultStatus(String status) {
        return status == null || status.isBlank() ? "ACTIVE" : status;
    }

    // Missing currency means SGD; any other currency is rejected because the payment service only charges SGD
    private String normaliseCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return CURRENCY;
        }
        if (!CURRENCY.equalsIgnoreCase(currency.trim())) {
            throw new InvalidRequestException("Currency must be " + CURRENCY + " (the only currency payments support)");
        }
        return CURRENCY;
    }
}
