package events.exception;

// The user already has a PENDING or CONFIRMED booking for this event -> 409
public class DuplicateBookingException extends EventException {

    public DuplicateBookingException(Integer eventId, Integer userId) {
        super("User " + userId + " already has an active booking for event " + eventId);
    }
}
