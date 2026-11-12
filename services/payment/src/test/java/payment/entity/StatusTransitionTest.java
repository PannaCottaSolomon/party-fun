package payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import payment.TestData;
import payment.exception.InvalidStateException;

class StatusTransitionTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
        "PENDING, PAID, true",
        "PENDING, FAILED, true",
        "PAID, REFUND_PENDING, true",
        "REFUND_PENDING, REFUNDED, true",
        "REFUND_PENDING, REFUND_FAILED, true",
        "REFUND_FAILED, REFUND_PENDING, true",
        "PENDING, REFUNDED, false",
        "PAID, REFUNDED, false",
        "PAID, PENDING, false",
        "FAILED, PAID, true",
        "REFUNDED, REFUND_PENDING, false",
        "REFUNDED, PAID, false",
        "REFUNDED, REFUND_FAILED, true",
        "REFUND_FAILED, PAID, false"
    })
    void paymentTransitions(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
        "PENDING, CONFIRMED, true",
        "PENDING, CANCELLED, true",
        "CONFIRMED, REFUNDED, true",
        "CONFIRMED, CANCELLED, true",
        "CANCELLED, REFUNDED, true",
        "CANCELLED, CONFIRMED, false",
        "CANCELLED, PENDING, false",
        "REFUNDED, CONFIRMED, false"
    })
    void bookingTransitions(BookingStatus from, BookingStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void cancelledBookingCanNeverBeReactivated() {
        Booking booking = TestData.booking(1, BookingStatus.CANCELLED, 1, 1, "10.00");

        assertThatThrownBy(() -> booking.transitionTo(BookingStatus.CONFIRMED))
                .isInstanceOf(InvalidStateException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void illegalPaymentTransitionThrowsAndKeepsState() {
        Payment payment = TestData.payment(1, TestData.booking(1, BookingStatus.PENDING, 1, 1, "10.00"),
                PaymentStatus.PENDING, null);

        assertThatThrownBy(() -> payment.markRefunded("re_1", LocalDateTime.now()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("PENDING");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void onlyAReversedRefundTurnsRefundedIntoRefundFailed() {
        Payment refunded = TestData.payment(1, TestData.booking(1, BookingStatus.REFUNDED, 1, 1, "10.00"),
                PaymentStatus.REFUNDED, "pi_1");

        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> refunded.markRefundFailed("x", 1, now)).isInstanceOf(InvalidStateException.class);
        refunded.markRefundReversed("Stripe reported refund re_1 as failed", now);
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(refunded.getRefundFailureReason()).isEqualTo("Stripe reported refund re_1 as failed");

        // Retrying it starts a new refund round.
        refunded.startRefund();
        assertThat(refunded.getRefundRound()).isEqualTo(1);
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    @Test
    void refundFailureDetailsAreRecordedAndClearedOnSuccess() {
        Payment payment = TestData.payment(1, TestData.booking(1, BookingStatus.CANCELLED, 1, 1, "10.00"),
                PaymentStatus.REFUND_PENDING, "pi_1");
        LocalDateTime failedAt = LocalDateTime.of(2026, 10, 23, 12, 0);

        payment.markRefundFailed("x".repeat(600), 3, failedAt);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(payment.getRefundFailureReason()).hasSize(500); // fits the column
        assertThat(payment.getRefundAttempts()).isEqualTo(3);
        assertThat(payment.getRefundFailedAt()).isEqualTo(failedAt);

        payment.startRefund();
        payment.markRefunded("re_2", failedAt.plusMinutes(5));
        assertThat(payment.getRefundFailureReason()).isNull();
        assertThat(payment.getRefundFailedAt()).isNull();
    }
}
