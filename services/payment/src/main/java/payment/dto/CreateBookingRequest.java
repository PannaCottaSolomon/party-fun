package payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /bookings}. {@code userId} is supplied by the caller for now; once the
 * Authentication Service exists it should come from the caller's JWT instead.
 *
 * <p>The three optional snapshot fields are the same as on {@code POST /payments/checkout}: without
 * {@code participantEmail} and {@code eventTitle} a later refund still works, but no {@code notify.refund} email can
 * be sent (the Notification Service requires both).
 */
public record CreateBookingRequest(
        @NotNull @Positive Integer userId,
        @NotNull @Positive Integer eventId,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal totalAmount,
        @Email @Size(max = 255) String participantEmail,
        @Size(max = 255) String participantName,
        @Size(max = 255) String eventTitle) {

    /** A booking without the notification snapshot (the Event Service's current request body). */
    public CreateBookingRequest(Integer userId, Integer eventId, Integer quantity, BigDecimal totalAmount) {
        this(userId, eventId, quantity, totalAmount, null, null, null);
    }
}
