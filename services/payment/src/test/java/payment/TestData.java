package payment;

import java.math.BigDecimal;

import org.springframework.test.util.ReflectionTestUtils;

import payment.config.PaymentProperties;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;

/** Builders for entities in a given state (IDs are normally assigned by MySQL). */
public final class TestData {

    private TestData() {
    }

    public static Booking booking(int bookingId, BookingStatus status, int userId, int eventId, String amount) {
        Booking booking = new Booking(userId, eventId, 2, new BigDecimal(amount));
        ReflectionTestUtils.setField(booking, "bookingId", bookingId);
        ReflectionTestUtils.setField(booking, "status", status);
        return booking;
    }

    /** Adds what POST /payments/checkout stores on a booking: the Event Service booking id and the email snapshot. */
    public static Booking withCheckoutSnapshot(Booking booking, String eventBookingId, String email, String eventTitle) {
        ReflectionTestUtils.setField(booking, "eventBookingId", eventBookingId);
        booking.recordNotificationDetails(email, "Pat", eventTitle);
        return booking;
    }

    public static Payment payment(int paymentId, Booking booking, PaymentStatus status, String paymentIntentId) {
        Payment payment = new Payment(booking, booking.getTotalAmount());
        ReflectionTestUtils.setField(payment, "paymentId", paymentId);
        ReflectionTestUtils.setField(payment, "status", status);
        ReflectionTestUtils.setField(payment, "stripePaymentIntentId", paymentIntentId);
        return payment;
    }

    public static PaymentProperties properties(int maxAttempts, boolean adminAlert) {
        return new PaymentProperties(
                new PaymentProperties.Refund(maxAttempts, 0),
                new PaymentProperties.Messaging(
                        "party.events",
                        "event.cancelled",
                        "payments.refunds.requested",
                        "payments.refund.dlx",
                        "payments.refunds.dlq",
                        "notifications",
                        "notify.refund",
                        "notify.admin_alert",
                        "http://localhost:15672"),
                new PaymentProperties.AdminAlert(adminAlert),
                new PaymentProperties.Recovery(null, null));
    }

    public static PaymentProperties properties() {
        return properties(3, true);
    }
}
