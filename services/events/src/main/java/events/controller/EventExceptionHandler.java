package events.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import events.client.PaymentUnavailableException;
import events.exception.BookingNotFoundException;
import events.exception.BookingNotPayableException;
import events.exception.DuplicateBookingException;
import events.exception.EventException;
import events.exception.EventFullException;
import events.exception.EventNotFoundException;
import events.exception.EventNotOpenException;
import events.exception.InvalidRequestException;

@RestControllerAdvice
public class EventExceptionHandler {

    // The event or booking does not exist
    @ExceptionHandler({ EventNotFoundException.class, BookingNotFoundException.class })
    public ResponseEntity<ProblemDetail> handleNotFound(EventException exception) {
        return problem(HttpStatus.NOT_FOUND, exception);
    }

    // The request clashes with the current state (event closed or full, the user already booked, or the booking
    // can no longer be paid)
    @ExceptionHandler({ EventNotOpenException.class, EventFullException.class, DuplicateBookingException.class,
            BookingNotPayableException.class })
    public ResponseEntity<ProblemDetail> handleConflict(EventException exception) {
        return problem(HttpStatus.CONFLICT, exception);
    }

    // A payment retry got no definite answer from the payment service. Nothing was recorded, and retrying is
    // safe: a payment that did go through is found and used instead of charging again.
    @ExceptionHandler(PaymentUnavailableException.class)
    public ResponseEntity<ProblemDetail> handlePaymentUnavailable(PaymentUnavailableException exception) {
        return unavailable("The payment service is unavailable, please try again");
    }

    // The request data breaks a rule (missing title or user id, bad times or capacity)
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(InvalidRequestException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception);
    }

    // The event row lock was not acquired within the lock timeout (very busy event) or a deadlock was
    // detected: the transaction was rolled back, so the client can safely try again
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException exception) {
        return unavailable("The event is busy, please try again");
    }

    // Any other database failure (e.g. PostgreSQL unreachable); the transaction was rolled back
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException exception) {
        return unavailable("The database is unavailable, please try again later");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, EventException exception) {
        return ResponseEntity.status(status)
                .body(ProblemDetail.forStatusAndDetail(status, exception.getMessage()));
    }

    private static ResponseEntity<ProblemDetail> unavailable(String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail));
    }
}
