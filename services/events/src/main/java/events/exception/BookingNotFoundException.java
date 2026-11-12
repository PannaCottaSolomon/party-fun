package events.exception;

// The booking does not exist, or belongs to another event -> 404
public class BookingNotFoundException extends EventException {

    public BookingNotFoundException(Integer eventId, Integer bookingId) {
        super("Booking " + bookingId + " not found for event " + eventId);
    }
}
