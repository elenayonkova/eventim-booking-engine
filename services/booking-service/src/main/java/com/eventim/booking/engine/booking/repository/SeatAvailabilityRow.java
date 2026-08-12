package com.eventim.booking.engine.booking.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.eventim.booking.engine.booking.domain.SeatStatus;

public record SeatAvailabilityRow(
        String seatLabel,
        SeatStatus status,
        UUID reservationId,
        OffsetDateTime expiresAt
) {
}
