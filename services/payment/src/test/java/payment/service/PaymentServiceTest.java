package payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import payment.TestData;
import payment.dto.CreatePaymentRequest;
import payment.dto.PaymentResponse;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.exception.NotFoundException;
import payment.exception.PaymentGatewayException;
import payment.repository.BookingRepository;
import payment.repository.PaymentRepository;
import payment.stripe.GatewayPaymentResult;
import payment.stripe.PaymentGateway;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway gateway;

    private PaymentService paymentService;
    private Booking booking;
    private Payment saved;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
        paymentService = new PaymentService(bookingRepository, paymentRepository, gateway,
                TransactionOperations.withoutTransaction(), clock);
        booking = TestData.booking(7, BookingStatus.PENDING, 1, 2, "40.00");
    }

    /** Stubs the "save PENDING payment, then reload it" steps of a successful create. */
    private void stubPendingPaymentSaved() {
        when(bookingRepository.findById(7)).thenReturn(Optional.of(booking));
        when(paymentRepository.existsByBookingBookingIdAndStatusIn(eq(7), any())).thenReturn(false);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "paymentId", 10);
            return saved;
        });
        when(paymentRepository.findById(10)).thenAnswer(invocation -> Optional.of(saved));
    }

    @Test
    void successfulStripePaymentMarksPaidAndConfirmsBooking() {
        stubPendingPaymentSaved();
        when(gateway.createPaymentIntent(eq(4000L), eq("pm_card_visa"), startsWith("payment-10-create-"), anyMap()))
                .thenReturn(GatewayPaymentResult.succeeded("pi_123", "card"));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(7, "pm_card_visa"));

        assertThat(response.paymentId()).isEqualTo(10);
        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.amount()).isEqualByComparingTo("40.00");
        assertThat(response.stripePaymentIntentId()).isEqualTo("pi_123");
        assertThat(response.paymentMethod()).isEqualTo("card");
        assertThat(response.paidAt()).isNotNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void declinedStripePaymentMarksFailedAndKeepsBookingPending() {
        stubPendingPaymentSaved();
        when(gateway.createPaymentIntent(anyLong(), anyString(), anyString(), anyMap()))
                .thenReturn(GatewayPaymentResult.failed("pi_declined", "Card declined (generic_decline)"));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(7, "pm_card_visa_chargeDeclined"));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureReason()).contains("declined");
        assertThat(response.stripePaymentIntentId()).isEqualTo("pi_declined");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void unconfirmedPaymentStaysPendingAndReturnsClientSecret() {
        stubPendingPaymentSaved();
        when(gateway.createPaymentIntent(eq(4000L), eq(null), anyString(), anyMap()))
                .thenReturn(GatewayPaymentResult.pending("pi_pending", "pi_pending_secret"));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(7, null));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.clientSecret()).isEqualTo("pi_pending_secret");
        assertThat(response.stripePaymentIntentId()).isEqualTo("pi_pending");
    }

    @Test
    void gatewayErrorMarksPaymentFailedAndPropagates() {
        stubPendingPaymentSaved();
        when(gateway.createPaymentIntent(anyLong(), anyString(), anyString(), anyMap()))
                .thenThrow(new PaymentGatewayException("Stripe payment request failed (code=null, status=500)"));

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7, "pm_card_visa")))
                .isInstanceOf(PaymentGatewayException.class);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void unknownBookingIsNotFoundAndStripeIsNotCalled() {
        when(bookingRepository.findById(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7, "pm_card_visa")))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void onlyPendingBookingsCanBePaid() {
        booking.transitionTo(BookingStatus.CANCELLED);
        when(bookingRepository.findById(7)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7, "pm_card_visa")))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("CANCELLED");
        verifyNoInteractions(gateway);
    }

    @Test
    void secondPaymentForSameBookingIsConflict() {
        when(bookingRepository.findById(7)).thenReturn(Optional.of(booking));
        when(paymentRepository.existsByBookingBookingIdAndStatusIn(eq(7), any())).thenReturn(true);

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7, "pm_card_visa")))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("already has");
        verifyNoInteractions(gateway);
    }

    @Test
    void getPayment() {
        when(paymentRepository.findById(3))
                .thenReturn(Optional.of(TestData.payment(3, booking, PaymentStatus.PAID, "pi_3")));

        PaymentResponse response = paymentService.get(3);

        assertThat(response.paymentId()).isEqualTo(3);
        assertThat(response.bookingId()).isEqualTo(7);
        assertThat(response.userId()).isEqualTo(1);
        assertThat(response.eventId()).isEqualTo(2);
        assertThat(response.clientSecret()).isNull();
    }

    @Test
    void getUnknownPaymentThrowsNotFound() {
        when(paymentRepository.findById(3)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.get(3)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listAllReturnsEveryPaymentNewestFirst() {
        Booking other = TestData.booking(8, BookingStatus.CONFIRMED, 5, 6, "15.00");
        when(paymentRepository.findAllByOrderByPaymentIdDesc()).thenReturn(List.of(
                TestData.payment(4, other, PaymentStatus.REFUND_FAILED, "pi_4"),
                TestData.payment(3, booking, PaymentStatus.PAID, "pi_3")));

        List<PaymentResponse> all = paymentService.listAll();

        assertThat(all).extracting(PaymentResponse::paymentId).containsExactly(4, 3);
        assertThat(all).extracting(PaymentResponse::userId).containsExactly(5, 1);
    }

    @Test
    void listByBooking() {
        when(bookingRepository.existsById(7)).thenReturn(true);
        when(paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(7)).thenReturn(List.of(
                TestData.payment(1, booking, PaymentStatus.FAILED, "pi_1"),
                TestData.payment(2, booking, PaymentStatus.PAID, "pi_2")));

        assertThat(paymentService.listByBooking(7)).extracting(PaymentResponse::paymentId).containsExactly(1, 2);
    }

    @Test
    void listByUnknownBookingIsNotFound() {
        when(bookingRepository.existsById(7)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.listByBooking(7)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listByUserUsesOwnDatabaseJoin() {
        when(paymentRepository.findByUserId(1)).thenReturn(List.of(TestData.payment(2, booking, PaymentStatus.PAID,
                "pi_2")));

        assertThat(paymentService.listByUser(1)).singleElement()
                .satisfies(p -> assertThat(p.userId()).isEqualTo(1));
        verify(paymentRepository).findByUserId(1);
    }

    /** A PENDING payment whose PaymentIntent the browser confirmed; both the plain and the locked load find it. */
    private Payment stubPendingPayment() {
        Payment payment = TestData.payment(10, booking, PaymentStatus.PENDING, "pi_1");
        when(paymentRepository.findById(10)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(10)).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void syncRecordsAPaymentStripeShowsAsSucceededWithoutTheWebhook() {
        stubPendingPayment();
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(GatewayPaymentResult.succeeded("pi_1", "card"));

        PaymentResponse response = paymentService.syncWithStripe(10);

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.paymentMethod()).isEqualTo("card");
        assertThat(response.paidAt()).isNotNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void syncMarksACanceledPaymentIntentFailed() {
        stubPendingPayment();
        when(gateway.retrievePaymentIntent("pi_1"))
                .thenReturn(GatewayPaymentResult.failed("pi_1", "PaymentIntent was canceled"));

        assertThat(paymentService.syncWithStripe(10).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void syncLeavesAPaymentStripeIsStillProcessingPending() {
        stubPendingPayment();
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(GatewayPaymentResult.pending("pi_1", "pi_1_secret"));

        PaymentResponse response = paymentService.syncWithStripe(10);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.clientSecret()).isNull();
    }

    @Test
    void syncDoesNotCallStripeForAPaymentThatIsNoLongerPending() {
        when(paymentRepository.findById(10))
                .thenReturn(Optional.of(TestData.payment(10, booking, PaymentStatus.PAID, "pi_1")));

        assertThat(paymentService.syncWithStripe(10).status()).isEqualTo(PaymentStatus.PAID);
        verifyNoInteractions(gateway);
    }

    @Test
    void syncOfAnUnknownPaymentIsNotFound() {
        when(paymentRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.syncWithStripe(99)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void creationIdempotencyKeyIsUniqueEvenForTheSamePaymentId() {
        // Payment ids restart at 1 in every database sharing the Stripe sandbox (CI, each developer's stack).
        String first = PaymentService.createIdempotencyKey(13);
        String second = PaymentService.createIdempotencyKey(13);

        assertThat(first).startsWith("payment-13-create-").isNotEqualTo(second);
        assertThat(second).matches("payment-13-create-[0-9a-f-]{36}");
    }
}
