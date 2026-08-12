package com.eventim.booking.engine.payment.api;

import java.util.UUID;

import com.eventim.booking.engine.payment.domain.RefundStatus;

public record RefundResponse(
        UUID refundId,
        UUID reservationId,
        UUID paymentId,
        RefundStatus status
) {
}
