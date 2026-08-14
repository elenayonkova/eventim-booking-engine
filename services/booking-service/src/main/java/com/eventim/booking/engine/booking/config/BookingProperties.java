package com.eventim.booking.engine.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed booking and payment-integration settings, including safe local defaults
 * for hold duration, reconciliation timeout, and HTTP connectivity.
 */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(
        Duration holdTtl,
        String paymentBaseUrl,
        Duration paymentConnectTimeout,
        Duration paymentReadTimeout,
        Duration paymentPendingTimeout
) {
}
