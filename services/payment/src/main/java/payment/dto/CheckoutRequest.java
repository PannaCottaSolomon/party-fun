package payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /payments/checkout}: the Event Service's synchronous ticket payment request.
 *
 * <p>party.fun uses a fixed price per event, so the total is always {@code ticketPrice × quantity}, computed
 * here with {@code BigDecimal} (never sent by the caller).
 *
 * @param eventBookingId   the Event Service's own booking id. Idempotency key: repeating the same request never
 *                         charges twice
 * @param paymentMethodId  Stripe PaymentMethod (e.g. test token {@code pm_card_visa}). When given, the payment is
 *                         confirmed immediately; when omitted, the PaymentIntent stays pending and the returned
 *                         {@code clientSecret} must be confirmed with Stripe.js (the webhook then completes it)
 * @param participantEmail participant's email, kept so this service can send {@code notify.refund} later
 * @param participantName  participant's display name for that email (optional)
 * @param eventTitle       event title for that email
 */
public record CheckoutRequest(
        @NotBlank @Size(max = 64) String eventBookingId,
        @NotNull @Positive Integer eventId,
        @NotNull @Positive Integer userId,
        @NotNull @Min(1) @Max(20) Integer quantity,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 6, fraction = 2) BigDecimal ticketPrice,
        @Size(max = 255) String paymentMethodId,
        @Email @Size(max = 255) String participantEmail,
        @Size(max = 255) String participantName,
        @Size(max = 255) String eventTitle) {
}
