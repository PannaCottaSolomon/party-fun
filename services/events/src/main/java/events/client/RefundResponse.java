package events.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The payment service's answer to {@code POST /payments/{paymentId}/refund}.
 *
 * @param paymentId the refunded payment
 * @param outcome   {@code REFUNDED}, {@code ALREADY_REFUNDED}, {@code PENDING} (Stripe has not finished; the payment
 *                  service completes it) or {@code SKIPPED} (never paid). A refund that failed for good is not an
 *                  answer but a 502.
 * @param message   human-readable description of the outcome
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundResponse(Integer paymentId, String outcome, String message) {
}
