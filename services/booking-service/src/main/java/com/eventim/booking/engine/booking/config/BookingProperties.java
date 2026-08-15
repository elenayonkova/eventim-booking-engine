package com.eventim.booking.engine.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed booking and payment-integration settings, including safe local defaults
 * for hold duration and HTTP connectivity.
 */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(
        Duration holdTtl,
        String paymentBaseUrl,
        Duration paymentConnectTimeout,
        Duration paymentReadTimeout,
        Duration paymentMissingTimeout
) {

    public BookingProperties {
        if (paymentMissingTimeout.compareTo(paymentReadTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "Payment missing timeout must exceed the payment read timeout");
        }
    }
}
