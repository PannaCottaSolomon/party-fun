package payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /payments}. The amount always comes from the booking, never from the client.
 *
 * @param bookingId       existing PENDING booking to pay for
 * @param paymentMethodId optional Stripe PaymentMethod (e.g. test token {@code pm_card_visa}). When given, the
 *                        PaymentIntent is confirmed immediately; when omitted, the frontend confirms it with the
 *                        returned {@code clientSecret} and the webhook completes the payment.
 */
public record CreatePaymentRequest(
        @NotNull @Positive Integer bookingId,
        @Size(max = 255) String paymentMethodId) {
}
