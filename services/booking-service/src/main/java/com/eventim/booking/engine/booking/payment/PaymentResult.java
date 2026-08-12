package com.eventim.booking.engine.booking.payment;

import java.util.UUID;

public record PaymentResult(
        UUID paymentId,
        UUID reservationId,
        long amount,
        String currency,
        String paymentMethodFingerprint,
        PaymentStatus status,
        String failureReason
) {
}
