package com.eventim.booking.engine.booking.api;

import java.time.OffsetDateTime;
import java.util.List;

import com.eventim.booking.engine.booking.domain.SeatStatus;

public record SeatAvailabilityResponse(
        String eventId,
        List<Seat> seats
) {
    public record Seat(
            String seatId,
            SeatStatus status,
            OffsetDateTime expiresAt
    ) {
    }
}
