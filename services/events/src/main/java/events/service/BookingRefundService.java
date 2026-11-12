package events.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import events.client.PaymentClient;
import events.client.PaymentRejectedException;
import events.client.PaymentResponse;
import events.client.PaymentUnavailableException;
import events.client.RefundResponse;
import events.entity.Booking;
import events.repository.BookingRepository;

/*  Compensation refunds: the card was charged, but the booking stopped being PENDING while the payment was in flight
    (the event was cancelled or deleted, or the booking expired), so the money has to go back.

    A refund is never fire-and-forget. It is first recorded as owed on the booking (refundRequiredAt, with the payment
    id in paymentReference), then tried at once. Until the payment service has taken it over, it stays owed and
    retryOwedRefunds() (run by PaymentRecoveryService every minute) tries again. Taken over means:
      200 from POST /payments/{id}/refund   REFUNDED, ALREADY_REFUNDED, PENDING (Stripe finishes it) or SKIPPED
      409                                   the payment service says the payment was never paid: nothing to refund
    Anything else keeps the refund owed: no answer, a 5xx, and also 401/403 (service key missing or wrong) or 404
    (wrong base URL), which need an operator and are logged as errors.

    A retry first looks the payment up (GET /payments/booking/{id}): the earlier attempt may have gone through with
    its answer lost, or the payment service may have given up on Stripe and moved the refund to its own refund DLQ
    (REFUND_FAILED, answered with 502). Either way it owns the refund now, and asking again would only start another
    Stripe round and another admin alert. */
@Service
public class BookingRefundService {

    private static final Logger log = LoggerFactory.getLogger(BookingRefundService.class);

    // Payment states in which the payment service owns the refund: done, in progress, or in its refund DLQ
    private static final Set<String> TAKEN_OVER = Set.of("REFUNDED", "REFUND_PENDING", "REFUND_FAILED");

    private final PaymentClient paymentClient;
    private final BookingFinalizerService finalizerService;
    private final BookingRepository bookingRepository;

    public BookingRefundService(PaymentClient paymentClient, BookingFinalizerService finalizerService,
            BookingRepository bookingRepository) {
        this.paymentClient = paymentClient;
        this.finalizerService = finalizerService;
        this.bookingRepository = bookingRepository;
    }

    // Records the refund of this payment as owed on every booking of the order it paid for, then tries it once (one
    // payment, so one refund). Never throws for payment service failures: the refund then stays owed and is retried.
    public void refund(List<Booking> order, Integer paymentId) {
        order.forEach(booking -> finalizerService.recordRefundOwed(booking, paymentId));
        Booking first = order.get(0);
        tryRefund(first, paymentId);
        // Taken over for the whole payment: the order's other bookings are settled the same way
        if (first.getRefundedAt() != null) {
            order.stream().skip(1)
                    .forEach(booking -> finalizerService.recordRefundSettled(booking, first.getRefundReference()));
        }
    }

    // Retries every owed refund, oldest first. Stops at the first one the payment service cannot be reached for:
    // it is down, so the rest wait for the next run.
    public void retryOwedRefunds() {
        List<Booking> owed = bookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc();
        for (Booking booking : owed) {
            if (!retry(booking)) {
                log.warn("Payment service unavailable: {} owed refund(s) wait for the next run", owed.size());
                return;
            }
        }
    }

    // Returns false if the payment service could not be reached
    private boolean retry(Booking booking) {
        Integer paymentId = Integer.valueOf(booking.getPaymentReference());
        Optional<String> state;
        try {
            state = paymentState(booking.getPaymentBookingId(), paymentId);
        } catch (PaymentUnavailableException exception) {
            return false;
        }
        if (state.isPresent() && TAKEN_OVER.contains(state.get())) {
            settle(booking, paymentId, state.get());
            return true;
        }
        return tryRefund(booking, paymentId);
    }

    // The payment's current status in the payment service, if it can be found
    private Optional<String> paymentState(Integer paymentBookingId, Integer paymentId) {
        if (paymentBookingId == null) {
            return Optional.empty();
        }
        try {
            return paymentClient.findPayments(paymentBookingId).stream()
                    .filter(payment -> paymentId.equals(payment.paymentId()))
                    .map(PaymentResponse::status)
                    .findFirst();
        } catch (PaymentRejectedException exception) {
            return Optional.empty();
        }
    }

    // One refund call. Returns false if the payment service could not be reached; the refund stays owed unless the
    // payment service took it over.
    private boolean tryRefund(Booking booking, Integer paymentId) {
        try {
            RefundResponse refund = paymentClient.refund(paymentId);
            settle(booking, paymentId, String.valueOf(refund.outcome()));
        } catch (PaymentRejectedException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT.value()) {
                settle(booking, paymentId, "NOT_PAID");
            } else {
                log.error("Refund of payment {} for booking {} was refused ({}: {}); it stays owed. Check "
                        + "events.payment.service-api-key and events.payment.base-url", paymentId, booking.getId(),
                        exception.getStatusCode(), exception.getMessage());
            }
        } catch (PaymentUnavailableException exception) {
            log.warn("Refund of payment {} for booking {} did not go through; it stays owed and is retried: {}",
                    paymentId, booking.getId(), exception.getMessage());
            return false;
        }
        return true;
    }

    private void settle(Booking booking, Integer paymentId, String reference) {
        finalizerService.recordRefundSettled(booking, reference);
        log.info("Refund of payment {} for booking {} taken over by the payment service: {}", paymentId,
                booking.getId(), reference);
    }
}
