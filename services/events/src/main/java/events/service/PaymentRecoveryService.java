package events.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import events.client.PaymentUnavailableException;
import events.entity.Booking;
import events.messaging.EventMessaging;
import events.repository.BookingRepository;

/*  Payment recovery, run by EventScheduler (events.payment.recovery-cron, every minute): the safety net that makes
    sure no money is left behind when the payment service, the network or this service failed half-way.

      1. Owed compensation refunds are retried until the payment service takes them over (BookingRefundService).
      2. PENDING bookings whose payment answer was lost (a timeout, an outage or a crash mid-payment) are checked
         with the payment service and confirmed if the card was charged after all, so nobody pays without getting
         their place, even if they never retry. Bookings younger than events.payment.reconcile-after are skipped
         (their own request may still be waiting for the answer), and so are bookings whose payment window closed
         more than RECONCILE_WINDOW ago. */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    // How long after its payment window closed a PENDING booking is still checked for a lost payment
    private static final Duration RECONCILE_WINDOW = Duration.ofDays(1);

    private final BookingRefundService refundService;
    private final BookingPaymentService paymentService;
    private final BookingRepository bookingRepository;
    private final EventMessaging eventMessaging;
    private final Duration reconcileAfter;
    private final Clock clock;

    // Production constructor: uses the UTC system clock
    @Autowired
    public PaymentRecoveryService(BookingRefundService refundService, BookingPaymentService paymentService,
            BookingRepository bookingRepository, EventMessaging eventMessaging,
            @Value("${events.payment.reconcile-after}") Duration reconcileAfter) {
        this(refundService, paymentService, bookingRepository, eventMessaging, reconcileAfter, Clock.systemUTC());
    }

    // Full constructor: lets unit tests inject a fixed Clock
    public PaymentRecoveryService(BookingRefundService refundService, BookingPaymentService paymentService,
            BookingRepository bookingRepository, EventMessaging eventMessaging, Duration reconcileAfter,
            Clock clock) {
        this.refundService = refundService;
        this.paymentService = paymentService;
        this.bookingRepository = bookingRepository;
        this.eventMessaging = eventMessaging;
        this.reconcileAfter = reconcileAfter;
        this.clock = clock;
    }

    public void recover() {
        refundService.retryOwedRefunds();
        reconcileUnconfirmedPayments();
    }

    // Step 2. One check per order (a signup's tickets share one payment). Stops at the first order the payment
    // service cannot be asked about: the rest wait for the next run.
    void reconcileUnconfirmedPayments() {
        LocalDateTime now = LocalDateTime.now(clock);
        Set<String> checked = new HashSet<>();
        for (Booking booking : bookingRepository.findUnconfirmedPayments(now.minus(reconcileAfter),
                now.minus(RECONCILE_WINDOW))) {
            String order = booking.getOrderId() != null ? booking.getOrderId() : "booking-" + booking.getId();
            if (!checked.add(order)) {
                continue;  // checked with an earlier ticket of its order
            }
            List<Booking> tickets = paymentService.unpaidOrderOf(booking);
            boolean confirmed;
            try {
                confirmed = paymentService.confirmIfPaid(tickets);
            } catch (PaymentUnavailableException exception) {
                log.warn("Payment service unavailable: unconfirmed payments are checked again on the next run: {}",
                        exception.getMessage());
                return;
            }
            if (confirmed) {
                log.info("Booking(s) {} confirmed by payment recovery: payment {} went through although its answer "
                        + "was lost", tickets.stream().map(Booking::getId).toList(), booking.getPaymentReference());
                eventMessaging.publishSignupConfirmation(tickets);
            }
        }
    }
}
