package events.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import events.dto.EventDto;
import events.entity.Booking;
import events.service.EventService;
import jakarta.validation.Valid;


@RestController
@RequestMapping("/events")
public class EventController {

    // Dependencies (constructor injection)
    private final EventService eventService;

    @Autowired
    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    // POST /events/{id}/signup?userId=..&paymentMethodId=..&quantity=.. : user buys tickets for an event and pays
    // paymentMethodId (a Stripe PaymentMethod such as pm_card_visa) is required for paid events only.
    // quantity (default 1, at most 10) is the number of tickets; each ticket is its own booking, and the signup's
    // bookings are one order, paid by one charge for all of them. Places are reserved all or nothing: 409 if fewer
    // than quantity places are left.
    // Returns 201 with the list of Bookings: all CONFIRMED if the payment succeeded (or the event is free), otherwise
    // all PENDING with their places held for 15 minutes (failureReason says why) so the payment can be retried.
    @PostMapping("/{id}/signup")
    @PreAuthorize("hasAnyRole('PARTICIPANT', 'ORGANISER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Booking> signUp(@PathVariable Integer id, @RequestParam Integer userId,
            @RequestParam(required = false) String paymentMethodId,
            @RequestParam(defaultValue = "1") Integer quantity) {
        return eventService.signUp(id, userId, paymentMethodId, quantity);
    }

    // GET /events/bookings?userId=.. : the user's bookings (one per ticket) in every status, newest first, so My Bookings
    // shows the same on every device. A literal path, so Spring matches it before GET /events/{id}.
    @GetMapping("/bookings")
    @PreAuthorize("hasAnyRole('PARTICIPANT', 'ORGANISER', 'ADMIN')")
    public List<Booking> listBookings(@RequestParam Integer userId) {
        return eventService.listBookingsForUser(userId);
    }

    // POST /events/{id}/bookings/{bookingId}/payment?paymentMethodId=.. : retry the payment of a PENDING booking
    // The charge pays every unpaid ticket of the booking's order (signup), so retrying any one of them pays for all.
    // Returns 200 with the Booking (CONFIRMED, or still PENDING with the new failureReason); a booking that is already
    // CONFIRMED (e.g. paid with another ticket of its order) is returned as it is, without charging.
    // 404 unknown booking, 409 expired or cancelled or payment window closed, 503 payment service unavailable.
    @PostMapping("/{id}/bookings/{bookingId}/payment")
    @PreAuthorize("hasAnyRole('PARTICIPANT', 'ORGANISER', 'ADMIN')")
    public Booking pay(@PathVariable Integer id, @PathVariable Integer bookingId,
            @RequestParam(required = false) String paymentMethodId) {
        return eventService.payForBooking(id, bookingId, paymentMethodId);
    }

    // POST /events : create a new event
    // The JSON body is bound to EventDto; the response is the saved event (201 Created).
    @PostMapping
    @PreAuthorize("hasAnyRole('ORGANISER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public EventDto create(@Valid @RequestBody EventDto event) {
        return eventService.create(event);
    }

    // GET /events : list every event
    @GetMapping
    @PreAuthorize("hasAnyRole('PARTICIPANT', 'ORGANISER', 'ADMIN')")
    public List<EventDto> listAll() {
        return eventService.listAll();
    }

    // GET /events/{id} : fetch a single event
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PARTICIPANT', 'ORGANISER', 'ADMIN')")
    public EventDto get(@PathVariable Integer id) {
        return eventService.get(id);
    }
    
    // PATCH /events/{id} : partial update
    // Only the non-null fields in the body are applied (no @Valid here, unlike create).
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANISER', 'ADMIN')")
    public EventDto update(@PathVariable Integer id, @RequestBody EventDto event) {
        return eventService.update(id, event);
    }

    // POST /events/{id}/cancel : cancel an event
    // Deletes the event as DELETE does, then publishes event.cancelled so the payment service refunds paid bookings.
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ORGANISER', 'ADMIN')")
    public EventDto cancel(@PathVariable Integer id) {
        return eventService.cancel(id);
    }

    // DELETE /events/{id} : delete an event
    // Also publishes an EVENT_DELETED notification (no refund request). Returns deleted event.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANISER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public EventDto delete(@PathVariable Integer id) {
        return eventService.delete(id);
    }
}
