package events.exception;

// The event does not exist (or was deleted) -> 404
public class EventNotFoundException extends EventException {

    public EventNotFoundException(Integer eventId) {
        super("Event " + eventId + " not found");
    }
}
