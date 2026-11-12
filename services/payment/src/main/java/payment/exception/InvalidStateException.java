package payment.exception;

/** The operation is not allowed in the resource's current status (e.g. refunding a PENDING payment). HTTP 409. */
public class InvalidStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidStateException(String message) {
        super(message);
    }
}
