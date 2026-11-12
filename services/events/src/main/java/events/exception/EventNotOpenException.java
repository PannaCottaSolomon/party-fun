package events.exception;

// A signup was attempted on an event whose status is not ACTIVE -> 409
public class EventNotOpenException extends EventException {

    public EventNotOpenException(Integer eventId) {
        super("Event " + eventId + " is not open for signups");
    }
}
