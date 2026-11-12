package payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code GET /admin/refunds/dlq/count}: the messages in {@code payments.refunds.dlq}, straight from RabbitMQ.
 *
 * @param ready          failed refunds waiting for an Admin (over AMQP: exact and immediate)
 * @param unacknowledged messages being handled right now (an Admin recovery in progress), from the management API,
 *                       whose statistics can lag a few seconds; null when only AMQP was reachable, which cannot tell
 * @param total          ready + unacknowledged
 * @param source         "management-api" (unacknowledged from the RabbitMQ management HTTP API) or "amqp" (fallback,
 *                       ready count only)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DlqCountResponse(String queue, long ready, Long unacknowledged, long total, String source) {
}
