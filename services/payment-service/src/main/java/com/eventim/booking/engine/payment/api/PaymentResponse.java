package com.eventim.booking.engine.payment.api;

import java.util.UUID;

import com.eventim.booking.engine.payment.domain.PaymentStatus;

public record PaymentResponse(
        UUID paymentId,
        UUID reservationId,
        long amount,
        String currency,
        String paymentMethodFingerprint,
        PaymentStatus status,
        String failureReason
) {
}
