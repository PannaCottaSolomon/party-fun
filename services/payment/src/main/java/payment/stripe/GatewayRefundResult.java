package payment.stripe;

/**
 * A refund Stripe accepted. {@code status} is Stripe's refund status: "succeeded", or "pending" /
 * "requires_action" when Stripe has accepted the refund but not completed it yet (the final result then
 * arrives by webhook). Failed or canceled refunds are reported as a {@code PaymentGatewayException} instead.
 */
public record GatewayRefundResult(String refundId, String status) {

    public static final String SUCCEEDED = "succeeded";

    /** True only when Stripe confirms the money has been returned. */
    public boolean succeeded() {
        return SUCCEEDED.equals(status);
    }

    /** Accepted by Stripe but not final yet: the result arrives by webhook. */
    public boolean inProgress() {
        return "pending".equals(status) || "requires_action".equals(status);
    }
}
