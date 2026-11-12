package payment.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The JSON body of every error response. Never contains stack traces or credentials. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public ErrorResponse {
        fieldErrors = fieldErrors == null ? null : Map.copyOf(fieldErrors);
    }
}
