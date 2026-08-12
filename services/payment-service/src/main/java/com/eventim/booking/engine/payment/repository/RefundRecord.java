package com.eventim.booking.engine.payment.repository;

import java.util.UUID;

import com.eventim.booking.engine.payment.domain.RefundStatus;

public record RefundRecord(
        UUID id,
        UUID reservationId,
        UUID paymentId,
        RefundStatus status
) {
}
