package com.eventim.booking.engine.booking.payment;

import java.util.UUID;

public record RefundResult(
        UUID refundId,
        UUID reservationId,
        UUID paymentId,
        RefundStatus status
) {
}
