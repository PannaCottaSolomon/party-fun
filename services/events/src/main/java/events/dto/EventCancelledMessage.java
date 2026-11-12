package events.dto;

/**
 * Body of the {@code event.cancelled} message this service publishes on the {@code party.events} exchange when an
 * event is cancelled ({@code POST /events/{id}/cancel}).
 *
 * <p>The payment service consumes it (queue {@code payment.refunds.requested}): it cancels the event's bookings on its
 * side and refunds every paid payment. Example:
 * <pre>
 * {"eventId":42,"requestId":"cancel-42","timestamp":"2026-10-06T10:00:00Z","reason":"Event cancelled by the organiser"}
 * </pre>
 *
 * @param eventId   id of the cancelled event (a JSON number, as the payment service expects)
 * @param requestId identifies this cancellation; the same for every cancel of the same event
 * @param timestamp when the event was cancelled, ISO-8601 in UTC
 * @param reason    human-readable reason
 */
public record EventCancelledMessage(Integer eventId, String requestId, String timestamp, String reason) {
}
