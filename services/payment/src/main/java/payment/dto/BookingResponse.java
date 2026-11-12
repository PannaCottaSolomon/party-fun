package payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import payment.entity.Booking;
import payment.entity.BookingStatus;

/**
 * Public view of a booking. {@code eventBookingId} and {@code eventTitle} are only set for checkout bookings.
 * The participant's email is never exposed. Timestamps are in the service's local time zone (UTC in containers).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingResponse(
        Integer bookingId,
        String eventBookingId,
        Integer userId,
        Integer eventId,
        String eventTitle,
        Integer quantity,
        BigDecimal totalAmount,
        String currency,
        BookingStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getBookingId(),
                booking.getEventBookingId(),
                booking.getUserId(),
                booking.getEventId(),
                booking.getEventTitle(),
                booking.getQuantity(),
                booking.getTotalAmount(),
                "SGD",
                booking.getStatus(),
                booking.getCreatedAt(),
                booking.getUpdatedAt());
    }
}
