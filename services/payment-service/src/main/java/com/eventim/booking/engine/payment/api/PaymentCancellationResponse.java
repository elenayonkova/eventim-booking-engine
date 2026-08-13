package com.eventim.booking.engine.payment.api;

import java.util.UUID;

public record PaymentCancellationResponse(
        UUID reservationId,
        PaymentResponse payment
) {
}
