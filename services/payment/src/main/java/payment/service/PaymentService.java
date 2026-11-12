package payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import payment.dto.CheckoutRequest;
import payment.dto.CreatePaymentRequest;
import payment.dto.PaymentResponse;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.entity.Payment;
import payment.entity.PaymentStatus;
import payment.exception.InvalidRequestException;
import payment.exception.InvalidStateException;
import payment.exception.NotFoundException;
import payment.exception.PaymentGatewayException;
import payment.repository.BookingRepository;
import payment.repository.PaymentRepository;
import payment.stripe.GatewayPaymentResult;
import payment.stripe.Money;
import payment.stripe.PaymentGateway;

/**
 * Creates payments and answers payment queries.
 *
 * <p>Payment creation is three steps so no database transaction stays open during the Stripe call:
 * save a PENDING row, call Stripe (synchronous HTTP), then record Stripe's answer. A payment is only PAID when
 * Stripe reports the PaymentIntent as {@code succeeded}; creating a PaymentIntent is never treated as success.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    /** Largest total the booking/payment columns (DECIMAL(10,2)) and Stripe's SGD limit accept. */
    private static final BigDecimal MAX_TOTAL = new BigDecimal("999999.99");
    private static final EnumSet<PaymentStatus> ACTIVE = EnumSet.of(PaymentStatus.PENDING, PaymentStatus.PAID);

    /** {@code POST /payments/checkout} result: the payment, and whether this call created it (201) or found it (200). */
    public record CheckoutResult(PaymentResponse payment, boolean created) {
    }

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    private final TransactionOperations transactions;
    private final Clock clock;

    public PaymentService(BookingRepository bookingRepository, PaymentRepository paymentRepository,
                          PaymentGateway gateway, TransactionOperations transactions, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.transactions = transactions;
        this.clock = clock;
    }

    public PaymentResponse create(CreatePaymentRequest request) {
        Payment pending = transactions.execute(status -> {
            Booking booking = bookingRepository.findById(request.bookingId())
                    .orElseThrow(() -> new NotFoundException("Booking " + request.bookingId() + " not found"));
            if (booking.getStatus() != BookingStatus.PENDING) {
                throw new InvalidStateException("Booking " + booking.getBookingId() + " is " + booking.getStatus()
                        + "; only PENDING bookings can be paid");
            }
            if (paymentRepository.existsByBookingBookingIdAndStatusIn(booking.getBookingId(),
                    EnumSet.of(PaymentStatus.PENDING, PaymentStatus.PAID))) {
                throw new InvalidStateException("Booking " + booking.getBookingId()
                        + " already has a pending or completed payment");
            }
            // The amount always comes from the booking, never from the client.
            return paymentRepository.save(new Payment(booking, booking.getTotalAmount()));
        });
        return charge(pending, request.paymentMethodId());
    }

    /**
     * Scenario 1, the Event Service's synchronous payment: creates (or reuses) this service's booking for the
     * Event Service booking, charges {@code ticketPrice × quantity} through Stripe and returns Stripe's result.
     *
     * <p>Idempotent per {@code eventBookingId}: if that booking already has a PENDING or PAID payment, it is
     * returned as-is and Stripe is not called again. After a FAILED (declined) attempt, a new attempt is made on
     * the same booking. The same {@code eventBookingId} with a different event, user, quantity or total is a 409.
     */
    public CheckoutResult checkout(CheckoutRequest request) {
        BigDecimal total = request.ticketPrice().multiply(BigDecimal.valueOf(request.quantity()));
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new InvalidRequestException("Total " + total + " SGD exceeds the maximum of " + MAX_TOTAL + " SGD");
        }
        CheckoutStep step;
        try {
            step = transactions.execute(status -> prepareCheckout(request, total));
        } catch (DataIntegrityViolationException e) {
            // A concurrent first checkout for the same eventBookingId created the booking a moment earlier.
            step = transactions.execute(status -> prepareCheckout(request, total));
        }
        if (step.existing()) {
            log.info("Checkout for event booking {} repeated; returning payment {} ({})", request.eventBookingId(),
                    step.payment().getPaymentId(), step.payment().getStatus());
            return new CheckoutResult(PaymentResponse.from(step.payment()), false);
        }
        return new CheckoutResult(charge(step.payment(), request.paymentMethodId()), true);
    }

    private CheckoutStep prepareCheckout(CheckoutRequest request, BigDecimal total) {
        Optional<Booking> found = bookingRepository.findByEventBookingIdForUpdate(request.eventBookingId());
        Booking booking;
        if (found.isPresent()) {
            booking = found.get();
            boolean same = booking.getEventId().equals(request.eventId())
                    && booking.getUserId().equals(request.userId())
                    && booking.getQuantity().equals(request.quantity())
                    && booking.getTotalAmount().compareTo(total) == 0;
            if (!same) {
                throw new InvalidStateException("eventBookingId " + request.eventBookingId()
                        + " was already used for a different event, user, quantity or total");
            }
        } else {
            booking = bookingRepository.saveAndFlush(Booking.forCheckout(request.eventBookingId(), request.userId(),
                    request.eventId(), request.quantity(), total));
        }
        booking.recordNotificationDetails(request.participantEmail(), request.participantName(), request.eventTitle());

        Optional<Payment> active = paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(booking.getBookingId())
                .stream().filter(p -> ACTIVE.contains(p.getStatus())).reduce((first, second) -> second);
        if (active.isPresent()) {
            return new CheckoutStep(active.get(), true);
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new InvalidStateException("Booking " + booking.getBookingId() + " is " + booking.getStatus()
                    + "; only PENDING bookings can be paid");
        }
        return new CheckoutStep(paymentRepository.save(new Payment(booking, booking.getTotalAmount())), false);
    }

    /** Calls Stripe for a saved PENDING payment (outside any transaction) and records Stripe's answer. */
    private PaymentResponse charge(Payment pending, String paymentMethodId) {
        Integer paymentId = pending.getPaymentId();

        GatewayPaymentResult result;
        try {
            result = gateway.createPaymentIntent(
                    Money.toCents(pending.getAmount()),
                    paymentMethodId,
                    createIdempotencyKey(paymentId),
                    Map.of("paymentId", paymentId.toString(),
                            "bookingId", pending.getBooking().getBookingId().toString()));
        } catch (PaymentGatewayException e) {
            log.warn("Stripe call failed for payment {}: {}", paymentId, e.getMessage());
            transactions.executeWithoutResult(status -> load(paymentId).markFailed(null));
            throw e;
        }

        Payment updated = transactions.execute(status -> applyResult(load(paymentId), result));
        return PaymentResponse.from(updated, result.clientSecret(), result.failureReason());
    }

    /**
     * Stripe idempotency key for creating one payment's PaymentIntent. The random part keeps it unique across every
     * database sharing the Stripe sandbox (payment ids restart at 1 in CI and on each developer's stack, and Stripe
     * would replay or reject a reused key for 24 hours). A fixed key per payment isn't needed: creation is sent once
     * per payment row (a repeated checkout returns the existing row, a retry after a decline is a new row), and the
     * Stripe SDK's own network retries reuse the key of the call.
     */
    static String createIdempotencyKey(Integer paymentId) {
        return "payment-" + paymentId + "-create-" + UUID.randomUUID();
    }

    private Payment applyResult(Payment payment, GatewayPaymentResult result) {
        switch (result.status()) {
            case SUCCEEDED -> {
                payment.markPaid(result.paymentIntentId(), result.paymentMethodType(), LocalDateTime.now(clock));
                confirmBooking(payment.getBooking());
            }
            case FAILED -> payment.markFailed(result.paymentIntentId());
            case PENDING -> payment.attachPaymentIntent(result.paymentIntentId());
            default -> throw new IllegalStateException("Unknown gateway status " + result.status());
        }
        return payment;
    }

    /**
     * {@code POST /payments/{paymentId}/sync}: asks Stripe for the PaymentIntent's current state and records it, so a
     * card the browser confirmed is PAID without waiting for Stripe's webhook (e.g. when {@code stripe listen} isn't
     * running). Only a PENDING payment changes: succeeded → PAID (booking CONFIRMED), canceled → FAILED, anything
     * else stays PENDING. Stripe is not called for a payment that is no longer PENDING, and a webhook arriving later
     * finds the payment already PAID.
     */
    public PaymentResponse syncWithStripe(Integer paymentId) {
        String paymentIntentId = transactions.execute(status -> {
            Payment payment = load(paymentId);
            return payment.getStatus() == PaymentStatus.PENDING ? payment.getStripePaymentIntentId() : null;
        });
        if (paymentIntentId == null) {
            return transactions.execute(status -> PaymentResponse.from(load(paymentId)));
        }

        GatewayPaymentResult result = gateway.retrievePaymentIntent(paymentIntentId);

        return transactions.execute(status -> {
            Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
            if (payment.getStatus() == PaymentStatus.PENDING && result.status() != GatewayPaymentResult.Status.PENDING) {
                applyResult(payment, result);
                log.info("Payment {} synced with Stripe: {}", paymentId, payment.getStatus());
            }
            return PaymentResponse.from(payment);
        });
    }

    /** A paid booking becomes CONFIRMED, unless it was cancelled meanwhile (cancelled stays cancelled). */
    static void confirmBooking(Booking booking) {
        if (booking.getStatus() == BookingStatus.PENDING) {
            booking.transitionTo(BookingStatus.CONFIRMED);
        } else {
            log.warn("Booking {} was paid while {}; leaving it unchanged", booking.getBookingId(), booking.getStatus());
        }
    }

    /** All payments, newest first. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> listAll() {
        return paymentRepository.findAllByOrderByPaymentIdDesc().stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Integer paymentId) {
        return PaymentResponse.from(load(paymentId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listByBooking(Integer bookingId) {
        if (!bookingRepository.existsById(bookingId)) {
            throw new NotFoundException("Booking " + bookingId + " not found");
        }
        return paymentRepository.findByBookingBookingIdOrderByPaymentIdAsc(bookingId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listByUser(Integer userId) {
        return paymentRepository.findByUserId(userId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    private record CheckoutStep(Payment payment, boolean existing) {
    }

    private Payment load(Integer paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
    }
}
