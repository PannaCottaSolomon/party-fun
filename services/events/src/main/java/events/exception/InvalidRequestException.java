package events.exception;

// The request data breaks a rule (missing title or user id, end before start, bad capacity) -> 400
public class InvalidRequestException extends EventException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
