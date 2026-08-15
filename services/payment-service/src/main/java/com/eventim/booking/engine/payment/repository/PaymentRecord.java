package com.eventim.booking.engine.payment.repository;

import java.util.UUID;

import com.eventim.booking.engine.payment.domain.PaymentStatus;

public record PaymentRecord(
        UUID id,
        UUID reservationId,
        long amount,
        String currency,
        String paymentMethodToken,
        String paymentMethodTokenDigest,
        PaymentStatus status,
        int attempt,
        String failureReason
) {
}
