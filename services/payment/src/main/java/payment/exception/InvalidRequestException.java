package payment.exception;

/** Input that passed basic validation but breaks a business rule. HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
