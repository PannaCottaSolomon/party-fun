package payment.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import payment.dto.BookingRefundResponse;
import payment.dto.CheckoutRequest;
import payment.dto.CreatePaymentRequest;
import payment.dto.PaymentResponse;
import payment.dto.RefundResultResponse;
import payment.service.PaymentService;
import payment.service.RefundService;

/**
 * Payment API. Payment state only changes through explicit operations (pay, refund, webhook):
 * there is deliberately no PUT, PATCH or DELETE.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;

    public PaymentController(PaymentService paymentService, RefundService refundService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request) {
        return paymentService.create(request);
    }

    /**
     * Scenario 1: the Event Service's synchronous ticket payment. 201 when a payment was created, 200 when the same
     * eventBookingId already had a pending or paid payment (nothing charged again).
     */
    @PostMapping("/checkout")
    public ResponseEntity<PaymentResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        PaymentService.CheckoutResult result = paymentService.checkout(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.payment());
    }

    @GetMapping
    public List<PaymentResponse> listAll() {
        return paymentService.listAll();
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable Integer paymentId) {
        return paymentService.get(paymentId);
    }

    @GetMapping("/booking/{bookingId}")
    public List<PaymentResponse> listByBooking(@PathVariable Integer bookingId) {
        return paymentService.listByBooking(bookingId);
    }

    @GetMapping("/user/{userId}")
    public List<PaymentResponse> listByUser(@PathVariable Integer userId) {
        return paymentService.listByUser(userId);
    }

    /**
     * Records the PaymentIntent's current state from Stripe (PAID once the browser confirmed the card), without
     * waiting for Stripe's webhook. Only changes a PENDING payment.
     */
    @PostMapping("/{paymentId}/sync")
    public PaymentResponse sync(@PathVariable Integer paymentId) {
        return paymentService.syncWithStripe(paymentId);
    }

    /** Admin (JWT or operator key) or the internal service (X-Service-Api-Key, e.g. the Event Service). */
    @PostMapping("/{paymentId}/refund")
    public RefundResultResponse refundPayment(@PathVariable Integer paymentId) {
        return refundService.refundPaymentManually(paymentId);
    }

    /** Admin only. */
    @PostMapping("/booking/{bookingId}/refund")
    public BookingRefundResponse refundBooking(@PathVariable Integer bookingId) {
        return refundService.refundBooking(bookingId);
    }
}
