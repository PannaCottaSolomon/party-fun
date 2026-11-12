package payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import payment.TestData;
import payment.dto.CheckoutRequest;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.InvalidRequestException;
import payment.exception.InvalidStateException;
import payment.repository.BookingRepository;
import payment.repository.PaymentRepository;
import payment.service.PaymentService.CheckoutResult;
import payment.stripe.GatewayPaymentResult;
import payment.stripe.PaymentGateway;

/** Scenario 1: the Event Service's synchronous {@code POST /payments/checkout}. Stripe is mocked. */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway gateway;

    private PaymentService paymentService;
    private final List<Payment> payments = new ArrayList<>();
    private Booking stored;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
        paymentService = new PaymentService(bookingRepository, paymentRepository, gateway,
                TransactionOperations.withoutTransaction(), clock);
        // A tiny in-memory "database" for one booking and its payments.
        lenient().when(bookingRepository.findByEventBookingIdForUpdate("evb-1"))
                .thenAnswer(inv -> Optional.ofNullable(stored));
        lenient().when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "bookingId", 7);
            return stored;
        });
        lenient().when(paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(7))
                .thenAnswer(inv -> List.copyOf(payments));
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment payment = inv.getArgument(0);
            ReflectionTestUtils.setField(payment, "paymentId", 10 + payments.size());
            payments.add(payment);
            return payment;
        });
        lenient().when(paymentRepository.findById(any())).thenAnswer(inv -> payments.stream()
                .filter(p -> p.getPaymentId().equals(inv.getArgument(0))).findFirst());
    }

    private static CheckoutRequest request(String paymentMethodId, int quantity, String price) {
        return new CheckoutRequest("evb-1", 2, 1, quantity, new BigDecimal(price), paymentMethodId,
                "pat@smu.edu.sg", "Pat", "Neon Party");
    }

    @Test
    void totalIsTicketPriceTimesQuantityInSgdCents() {
        when(gateway.createPaymentIntent(eq(3750L), eq("pm_card_visa"), startsWith("payment-10-create-"), anyMap()))
                .thenReturn(GatewayPaymentResult.succeeded("pi_1", "card"));

        CheckoutResult result = paymentService.checkout(request("pm_card_visa", 3, "12.50"));

        assertThat(result.created()).isTrue();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.payment().amount()).isEqualByComparingTo("37.50");
        assertThat(result.payment().eventBookingId()).isEqualTo("evb-1");
        assertThat(stored.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        // The snapshot that later lets Payment send notify.refund itself.
        assertThat(stored.getParticipantEmail()).isEqualTo("pat@smu.edu.sg");
        assertThat(stored.getParticipantName()).isEqualTo("Pat");
        assertThat(stored.getEventTitle()).isEqualTo("Neon Party");
    }

    @Test
    void declinedCardIsFailedNotPaid() {
        when(gateway.createPaymentIntent(anyLong(), eq("pm_card_visa_chargeDeclined"), anyString(), anyMap()))
                .thenReturn(GatewayPaymentResult.failed("pi_2", "Card declined (generic_decline)"));

        CheckoutResult result = paymentService.checkout(request("pm_card_visa_chargeDeclined", 1, "10.00"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains("declined");
        assertThat(stored.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void paymentNeedingConfirmationStaysPending() {
        when(gateway.createPaymentIntent(eq(1000L), isNull(), anyString(), anyMap()))
                .thenReturn(GatewayPaymentResult.pending("pi_3", "pi_3_secret"));

        CheckoutResult result = paymentService.checkout(request(null, 1, "10.00"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.payment().clientSecret()).isEqualTo("pi_3_secret");
        assertThat(result.payment().paidAt()).isNull();
        assertThat(stored.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void repeatedCheckoutReturnsTheExistingPaymentWithoutChargingAgain() {
        when(gateway.createPaymentIntent(anyLong(), anyString(), anyString(), anyMap()))
                .thenReturn(GatewayPaymentResult.succeeded("pi_1", "card"));
        CheckoutResult first = paymentService.checkout(request("pm_card_visa", 2, "10.00"));

        CheckoutResult again = paymentService.checkout(request("pm_card_visa", 2, "10.00"));

        assertThat(again.created()).isFalse();
        assertThat(again.payment().paymentId()).isEqualTo(first.payment().paymentId());
        assertThat(again.payment().status()).isEqualTo(PaymentStatus.PAID);
        verify(gateway, org.mockito.Mockito.times(1)).createPaymentIntent(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void retryAfterADeclineMakesANewAttemptOnTheSameBooking() {
        when(gateway.createPaymentIntent(anyLong(), anyString(), anyString(), anyMap()))
                .thenReturn(GatewayPaymentResult.failed("pi_2", "Card declined"))
                .thenReturn(GatewayPaymentResult.succeeded("pi_4", "card"));
        paymentService.checkout(request("pm_card_visa_chargeDeclined", 1, "10.00"));

        CheckoutResult retry = paymentService.checkout(request("pm_card_visa", 1, "10.00"));

        assertThat(retry.created()).isTrue();
        assertThat(retry.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(payments).extracting(Payment::getStatus).containsExactly(PaymentStatus.FAILED, PaymentStatus.PAID);
    }

    @Test
    void sameEventBookingIdWithDifferentDetailsIsAConflict() {
        stored = TestData.withCheckoutSnapshot(TestData.booking(7, BookingStatus.PENDING, 1, 2, "20.00"), "evb-1",
                null, null);

        assertThatThrownBy(() -> paymentService.checkout(request("pm_card_visa", 3, "10.00")))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("evb-1");
        verifyNoInteractions(gateway);
    }

    @Test
    void cancelledBookingCannotBePaid() {
        stored = TestData.withCheckoutSnapshot(TestData.booking(7, BookingStatus.CANCELLED, 1, 2, "10.00"), "evb-1",
                null, null);
        ReflectionTestUtils.setField(stored, "quantity", 1);

        assertThatThrownBy(() -> paymentService.checkout(request("pm_card_visa", 1, "10.00")))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("CANCELLED");
        verifyNoInteractions(gateway);
    }

    @Test
    void totalAboveTheLimitIsRejected() {
        assertThatThrownBy(() -> paymentService.checkout(request("pm_card_visa", 20, "99999.99")))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(gateway);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentFirstCheckoutReusesTheBookingTheOtherRequestCreated() {
        Booking other = TestData.withCheckoutSnapshot(TestData.booking(7, BookingStatus.PENDING, 1, 2, "10.00"),
                "evb-1", null, null);
        ReflectionTestUtils.setField(other, "quantity", 1);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            stored = other; // the other request won the race
            throw new DataIntegrityViolationException("duplicate event_booking_id");
        });
        when(gateway.createPaymentIntent(anyLong(), anyString(), anyString(), anyMap()))
                .thenReturn(GatewayPaymentResult.succeeded("pi_1", "card"));

        CheckoutResult result = paymentService.checkout(request("pm_card_visa", 1, "10.00"));

        assertThat(result.payment().bookingId()).isEqualTo(7);
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.PAID);
    }
}
