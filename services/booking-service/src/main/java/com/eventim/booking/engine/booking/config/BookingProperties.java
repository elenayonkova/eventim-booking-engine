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

    public BookingProperties {
        if (holdTtl == null) {
            holdTtl = Duration.ofMinutes(5);
        }
        if (paymentBaseUrl == null || paymentBaseUrl.isBlank()) {
            paymentBaseUrl = "http://localhost:8081";
        }
        if (paymentConnectTimeout == null) {
            paymentConnectTimeout = Duration.ofSeconds(2);
        }
        if (paymentReadTimeout == null) {
            paymentReadTimeout = Duration.ofSeconds(10);
        }
        if (paymentPendingTimeout == null) {
            paymentPendingTimeout = Duration.ofMinutes(3);
        }
    }
}
