package com.eventim.booking.engine.booking.payment;

import java.util.UUID;

public record ChargePayment(
        UUID reservationId,
        long amount,
        String currency,
        String paymentMethodToken,
        PaymentSimulation simulation
) {
}
