package events.exception;

// A payment was attempted for a booking that is not PENDING, or whose payment window has closed -> 409
public class BookingNotPayableException extends EventException {

    public BookingNotPayableException(Integer bookingId, String reason) {
        super("Booking " + bookingId + " cannot be paid: " + reason);
    }
}
