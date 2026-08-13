package com.eventim.booking.engine.booking.payment;

import java.util.UUID;

public record PaymentCancellationResult(
        UUID reservationId,
        PaymentResult payment
) {
}
