package events.exception;

// Base class for this service's business errors. Each subclass is one kind of failure, and
// EventExceptionHandler maps it to an HTTP status:
//   EventNotFoundException, BookingNotFoundException                       -> 404
//   EventNotOpenException, EventFullException, DuplicateBookingException,
//   BookingNotPayableException                                             -> 409
//   InvalidRequestException                                                -> 400
public abstract class EventException extends RuntimeException {

    protected EventException(String message) {
        super(message);
    }
}
