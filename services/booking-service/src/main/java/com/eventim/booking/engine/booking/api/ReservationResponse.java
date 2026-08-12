package com.eventim.booking.engine.booking.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.eventim.booking.engine.booking.domain.ReservationStatus;

public record ReservationResponse(
        UUID reservationId,
        String eventId,
        List<String> seatIds,
        ReservationStatus status,
        OffsetDateTime expiresAt
) {
}
