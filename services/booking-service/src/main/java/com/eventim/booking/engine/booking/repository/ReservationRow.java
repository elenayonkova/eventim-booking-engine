package com.eventim.booking.engine.booking.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.eventim.booking.engine.booking.domain.ReservationStatus;

public record ReservationRow(
        UUID id,
        String eventId,
        ReservationStatus status,
        OffsetDateTime expiresAt,
        UUID paymentId,
        long checkoutAmount,
        String checkoutCurrency,
        String paymentMethodTokenDigest,
        String paymentFailureReason
) {
}
