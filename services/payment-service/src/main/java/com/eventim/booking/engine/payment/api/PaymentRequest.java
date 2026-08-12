package com.eventim.booking.engine.payment.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
        @NotNull UUID reservationId,
        @Positive long amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank @Size(max = 256) String paymentMethodToken
) {
}
