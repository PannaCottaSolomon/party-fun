package events.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import events.client.PaymentClient;
import events.client.PaymentRejectedException;
import events.client.PaymentResponse;
import events.entity.Booking;
import events.entity.BookingStatus;
import events.entity.Event;
import events.repository.BookingRepository;
import events.repository.EventRepository;

/*  Charges an order through the payment service (signup step 2, and payment retries). An order is the PENDING
    bookings of one signup, one per ticket: they are paid by ONE charge for all of them, and confirmed together.

    Runs OUTSIDE any transaction: no row lock or database connection is held while the payment service (and Stripe)
    is called. Each database change is its own short transaction in BookingFinalizerService.

    Outcomes, all applied to every passed Booking:
      PAID      -> CONFIRMED (returns true)
      FAILED    -> stays PENDING with failedAt/failureReason, so the user can retry until expiresAt (returns false)
      PENDING   -> stays PENDING: the payment needs more customer action (returns false)
      no answer -> PaymentUnavailableException; nothing is recorded and a retry is safe (see reconcile below).
                   If the card was charged after all, PaymentRecoveryService confirms the order later
                   (confirmIfPaid), even if the user never retries.

    Saga compensation: if the money was taken but the order's bookings are no longer PENDING (they expired or the
    event was cancelled while the payment was in flight), the payment is refunded through BookingRefundService, which
    keeps the refund owed until the payment service has taken it over. */
@Service
public class BookingPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BookingPaymentService.class);

    private final PaymentClient paymentClient;
    private final BookingFinalizerService finalizerService;
    private final BookingRefundService refundService;
    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;  // the event's title, for the payment service's refund email

    @Autowired
    public BookingPaymentService(PaymentClient paymentClient, BookingFinalizerService finalizerService,
            BookingRefundService refundService, BookingRepository bookingRepository,
            EventRepository eventRepository) {
        this.paymentClient = paymentClient;
        this.finalizerService = finalizerService;
        this.refundService = refundService;
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
    }

    // True if this amount has to be charged (a null or zero ticket price is a free event)
    public static boolean requiresPayment(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    // The order a booking belongs to that still has to be paid: the PENDING bookings of its signup, oldest first,
    // with this very booking in it (so the caller sees its outcome). A booking without an order is an order of one.
    public List<Booking> unpaidOrderOf(Booking booking) {
        if (booking.getOrderId() == null) {
            return List.of(booking);
        }
        List<Booking> order = new ArrayList<>();
        for (Booking ticket : bookingRepository.findByOrderIdOrderByIdAsc(booking.getOrderId())) {
            if (ticket.getId().equals(booking.getId())) {
                order.add(booking);
            } else if (ticket.getStatus() == BookingStatus.PENDING) {
                order.add(ticket);
            }
        }
        return order;
    }

    // Charges an order (the PENDING bookings of one signup) once, for all of its tickets, and records the outcome on
    // every one of them. Returns true if they are now CONFIRMED.
    // Throws PaymentUnavailableException when the payment service gave no definite answer.
    public boolean pay(List<Booking> order, String paymentMethodId) {
        Booking first = order.get(0);
        BigDecimal total = order.stream().map(Booking::getTotalAmount)
                .map(amount -> amount == null ? BigDecimal.ZERO : amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // A free event has nothing to charge: confirm straight away
        if (!requiresPayment(total)) {
            return finalizerService.confirm(order, null);
        }

        // 1. The payment service charges its own bookings: create one there for the whole order, then reuse it on
        // every retry
        Integer paymentBookingId = first.getPaymentBookingId();
        if (paymentBookingId == null) {
            try {
                String eventTitle = eventRepository.findById(first.getEventId().longValue())
                        .map(Event::getTitle).orElse(null);
                paymentBookingId = paymentClient.createBooking(first.getUserId(), first.getEventId(), order.size(),
                        total, eventTitle);
            } catch (PaymentRejectedException exception) {
                finalizerService.recordPaymentFailure(order, exception.getMessage());
                return false;
            }
            finalizerService.linkPaymentBooking(order, paymentBookingId);
        }

        // 2. Charge it
        PaymentResponse payment;
        try {
            payment = paymentClient.createPayment(paymentBookingId, paymentMethodId);
        } catch (PaymentRejectedException exception) {
            Optional<PaymentResponse> paid = reconcile(exception, paymentBookingId);
            if (paid.isEmpty()) {
                finalizerService.recordPaymentFailure(order, exception.getMessage());
                return false;
            }
            payment = paid.get();
        }

        // 3. Record the outcome
        if (payment.isPaid()) {
            return confirmOrRefund(order, payment);
        }
        if (payment.isFailed()) {
            finalizerService.recordPaymentFailure(order, payment.failureReason());
            return false;
        }
        log.info("Payment {} for booking(s) {} is {}: left PENDING", payment.paymentId(), ids(order),
                payment.status());
        return false;
    }

    // Recovery of an order whose payment answer was lost (PaymentRecoveryService): if the payment service shows a
    // PAID payment for it, confirm it, or refund it if its bookings stopped being PENDING meanwhile.
    // Returns true if the order is now CONFIRMED. Throws PaymentUnavailableException if the payment service
    // cannot be asked.
    public boolean confirmIfPaid(List<Booking> order) {
        Optional<PaymentResponse> paid = findPaidPayment(order.get(0).getPaymentBookingId());
        return paid.isPresent() && confirmOrRefund(order, paid.get());
    }

    // A 409 means the payment-side booking already has a PENDING or PAID payment, e.g. an earlier attempt whose
    // response was lost to a timeout. If one of them is PAID, that is our payment: use it instead of failing.
    private Optional<PaymentResponse> reconcile(PaymentRejectedException exception, Integer paymentBookingId) {
        if (exception.getStatusCode() != HttpStatus.CONFLICT.value()) {
            return Optional.empty();
        }
        return findPaidPayment(paymentBookingId);
    }

    // The PAID payment of a payment-side booking, if there is one (a rejected lookup counts as none)
    private Optional<PaymentResponse> findPaidPayment(Integer paymentBookingId) {
        try {
            return paymentClient.findPayments(paymentBookingId).stream()
                    .filter(PaymentResponse::isPaid)
                    .findFirst();
        } catch (PaymentRejectedException lookupRejected) {
            return Optional.empty();
        }
    }

    // PENDING -> CONFIRMED for the whole order, or, if its bookings stopped being PENDING while the payment was in
    // flight, refund the payment (saga compensation) and report the bookings' real status.
    private boolean confirmOrRefund(List<Booking> order, PaymentResponse payment) {
        String paymentReference = payment.paymentId().toString();
        if (finalizerService.confirm(order, paymentReference)) {
            return true;
        }
        int confirmedByThisPayment = 0;
        for (Booking booking : order) {
            Optional<Booking> current = bookingRepository.findById(booking.getId());
            current.ifPresent(found -> booking.setStatus(found.getStatus()));
            if (current.isPresent() && current.get().getStatus() == BookingStatus.CONFIRMED
                    && paymentReference.equals(current.get().getPaymentReference())) {
                booking.setConfirmedAt(current.get().getConfirmedAt());
                booking.setPaymentReference(paymentReference);
                confirmedByThisPayment++;
            }
        }

        // Already confirmed by this very payment (a concurrent payment retry, or the recovery job, got there first):
        // that is not a reason to refund it
        if (confirmedByThisPayment == order.size()) {
            return true;
        }
        if (confirmedByThisPayment > 0) {
            // Cannot happen: an order is confirmed as a whole (BookingFinalizerService.confirm). Keep the money rather
            // than refund a payment that also pays for confirmed places.
            log.error("Payment {} confirmed only {} of booking(s) {}: not refunded, check this order",
                    payment.paymentId(), confirmedByThisPayment, ids(order));
            return false;
        }
        log.warn("Payment {} succeeded but booking(s) {} are {}: refunding it", payment.paymentId(), ids(order),
                first(order).getStatus());
        refundService.refund(order, payment.paymentId());
        return false;
    }

    private static Booking first(List<Booking> order) {
        return order.get(0);
    }

    private static List<Integer> ids(List<Booking> order) {
        return order.stream().map(Booking::getId).toList();
    }
}
