package payment.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import payment.dto.ErrorResponse;

/**
 * Turns every exception into a structured JSON {@link ErrorResponse}. Unexpected errors are logged
 * in full but the client only ever sees a generic message: no stack traces, SQL, or credentials.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
                                                                HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON", req, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'", req, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Missing header '" + ex.getHeaderName() + "'", req, null);
    }

    @ExceptionHandler({InvalidRequestException.class, InvalidWebhookException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req, null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req, null);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, null);
    }

    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(InvalidStateException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, null);
    }

    @ExceptionHandler({PaymentGatewayException.class, RefundFailedException.class})
    public ResponseEntity<ErrorResponse> handleGateway(RuntimeException ex, HttpServletRequest req) {
        log.warn("Upstream payment error on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), req, null);
    }

    @ExceptionHandler(ServiceNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleNotConfigured(ServiceNotConfiguredException ex,
                                                             HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req, null);
    }

    @ExceptionHandler({AmqpException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class})
    public ResponseEntity<ErrorResponse> handleUnavailable(Exception ex, HttpServletRequest req) {
        log.error("Dependency unavailable on {}", req.getRequestURI(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "A required service (database or message broker) is unavailable; please retry later", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex, HttpServletRequest req) {
        // Spring's own web exceptions (404 no route, 405 method not allowed, 415...) carry their status.
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode code = springError.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            String message = status != null ? status.getReasonPhrase() : "Request failed";
            return build(code, message, req, null);
        }
        log.error("Unexpected error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req, null);
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatusCode code, String message, HttpServletRequest req,
                                                       Map<String, String> fieldErrors) {
        HttpStatus status = HttpStatus.resolve(code.value());
        String error = status != null ? status.getReasonPhrase() : "Error";
        ErrorResponse body = new ErrorResponse(Instant.now(), code.value(), error, message, req.getRequestURI(),
                fieldErrors);
        return ResponseEntity.status(code).body(body);
    }
}
