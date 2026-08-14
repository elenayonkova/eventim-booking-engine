package com.eventim.booking.engine.booking.repository;

import java.util.UUID;

import com.eventim.booking.engine.booking.domain.SeatStatus;

public record ReservationSeatRow(
        UUID id,
        String seatLabel,
        SeatStatus status,
        UUID currentReservationId
) {
}
