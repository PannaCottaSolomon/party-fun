package events.exception;

// A signup was attempted on an event without enough places left for the tickets requested -> 409
public class EventFullException extends EventException {

    public EventFullException(Integer eventId) {
        super("Event " + eventId + " has no remaining capacity");
    }

    // More tickets requested than places left (but some places remain)
    public EventFullException(Integer eventId, int requested, int remaining) {
        super(remaining <= 0
                ? "Event " + eventId + " has no remaining capacity"
                : "Event " + eventId + " has only " + remaining + " place(s) left, " + requested + " requested");
    }
}
