package com.eventim.booking.engine.booking.api;

import java.util.UUID;

import com.eventim.booking.engine.booking.domain.ReservationStatus;

public record CheckoutResponse(
        UUID reservationId,
        UUID paymentId,
        ReservationStatus status,
        long amount,
        String currency,
        String failureReason
) {
}
