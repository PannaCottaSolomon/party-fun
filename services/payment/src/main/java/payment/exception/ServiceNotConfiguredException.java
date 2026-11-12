package payment.exception;

/** A feature cannot be used because a required setting is missing (e.g. no Stripe publishable key). Mapped to 503. */
public class ServiceNotConfiguredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceNotConfiguredException(String message) {
        super(message);
    }
}
