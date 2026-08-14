package com.eventim.booking.engine.booking.repository;

import java.util.UUID;

import com.eventim.booking.engine.booking.domain.SeatStatus;

public record SeatRow(UUID id, String seatLabel, SeatStatus status, long priceAmount) {
}
