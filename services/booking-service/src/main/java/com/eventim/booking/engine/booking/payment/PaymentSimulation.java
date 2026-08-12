package com.eventim.booking.engine.booking.payment;

public record PaymentSimulation(Long delayMs, String failure) {

    public static final long MAX_DELAY_MS = 60_000;

    public PaymentSimulation {
        if (delayMs != null && (delayMs < 0 || delayMs > MAX_DELAY_MS)) {
            throw new IllegalArgumentException(
                    "Simulation delay must be between 0 and " + MAX_DELAY_MS + " milliseconds");
        }
    }
}
