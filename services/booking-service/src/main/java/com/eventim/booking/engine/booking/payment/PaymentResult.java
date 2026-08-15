package com.eventim.booking.engine.booking.payment;

import java.util.UUID;

public record PaymentResult(
        UUID paymentId,
        UUID reservationId,
        long amount,
        String currency,
        String paymentMethodTokenDigest,
        PaymentStatus status,
        String failureReason
) {
}
