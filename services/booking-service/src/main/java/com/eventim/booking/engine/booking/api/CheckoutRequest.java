package com.eventim.booking.engine.booking.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CheckoutRequest(
        @NotNull UUID reservationId,
        @NotBlank @Size(max = 256) String paymentMethodToken
) {
}
