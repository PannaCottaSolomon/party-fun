package payment.dto;

import java.util.List;

/** Result of {@code POST /payments/booking/{bookingId}/refund}: one entry per payment of the booking. */
public record BookingRefundResponse(Integer bookingId, List<RefundResultResponse> results) {

    public BookingRefundResponse {
        results = List.copyOf(results);
    }
}
