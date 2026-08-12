package com.eventim.booking.engine.payment.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record RefundRequest(@NotNull UUID reservationId) {
}
