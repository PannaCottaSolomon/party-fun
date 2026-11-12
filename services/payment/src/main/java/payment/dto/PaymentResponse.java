package payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import payment.entity.Booking;
import payment.entity.Payment;
import payment.entity.PaymentStatus;

/**
 * Public view of a payment. {@code clientSecret} and {@code failureReason} only appear in the response to
 * {@code POST /payments} / {@code POST /payments/checkout}. {@code eventBookingId} is the Event Service's booking id
 * (checkout payments only). Timestamps are in the service's local time zone (UTC in containers).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        Integer paymentId,
        Integer bookingId,
        String eventBookingId,
        Integer userId,
        Integer eventId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String stripePaymentIntentId,
        String stripeRefundId,
        String paymentMethod,
        LocalDateTime paidAt,
        LocalDateTime refundedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String clientSecret,
        String failureReason) {

    public static PaymentResponse from(Payment payment) {
        return from(payment, null, null);
    }

    public static PaymentResponse from(Payment payment, String clientSecret, String failureReason) {
        Booking booking = payment.getBooking();
        return new PaymentResponse(
                payment.getPaymentId(),
                booking.getBookingId(),
                booking.getEventBookingId(),
                booking.getUserId(),
                booking.getEventId(),
                payment.getAmount(),
                "SGD",
                payment.getStatus(),
                payment.getStripePaymentIntentId(),
                payment.getStripeRefundId(),
                payment.getPaymentMethod(),
                payment.getPaidAt(),
                payment.getRefundedAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                clientSecret,
                failureReason);
    }
}
