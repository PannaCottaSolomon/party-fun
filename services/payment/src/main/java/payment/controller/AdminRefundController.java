package payment.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import payment.dto.AdminRetryResponse;
import payment.dto.DlqCountResponse;
import payment.dto.FailedRefundsResponse;
import payment.service.AdminRefundService;

/**
 * Admin dashboard API for failed refunds (Admin → frontend → Payment Service; the frontend never talks to RabbitMQ).
 * Every endpoint requires an Admin: a JWT with role ADMIN, or the operator key (see {@code AuthConfig}).
 */
@RestController
public class AdminRefundController {

    private final AdminRefundService adminRefundService;

    public AdminRefundController(AdminRefundService adminRefundService) {
        this.adminRefundService = adminRefundService;
    }

    /** How many failed refunds wait in payments.refunds.dlq (ready / unacknowledged / total), from RabbitMQ. */
    @GetMapping("/admin/refunds/dlq/count")
    public DlqCountResponse deadLetterCount() {
        return adminRefundService.deadLetterCount();
    }

    /** The REFUND_FAILED payments (MySQL), with how many DLQ messages each has. */
    @GetMapping("/admin/refunds/failed")
    public FailedRefundsResponse failedRefunds() {
        return adminRefundService.failedRefunds();
    }

    /**
     * Recovers one failed refund now (synchronous): checks Stripe, refunds again if needed, and removes the payment's
     * DLQ message only if it succeeded. 409 if it is not REFUND_FAILED or another Admin is recovering it.
     */
    @PostMapping("/admin/refunds/{paymentId}/retry")
    public AdminRetryResponse retry(@PathVariable Integer paymentId) {
        return adminRefundService.retry(paymentId);
    }

    /**
     * Deprecated alias of {@code POST /admin/refunds/{paymentId}/retry}, kept so existing clients keep working. It
     * used to queue refund.reprocess.requested (that queue no longer exists); it now recovers synchronously.
     */
    @Deprecated
    @PostMapping("/payments/{paymentId}/retry-refund")
    public AdminRetryResponse retryRefundAlias(@PathVariable Integer paymentId) {
        return adminRefundService.retry(paymentId);
    }
}
