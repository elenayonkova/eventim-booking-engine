package com.eventim.booking.engine.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "booking")
public record BookingProperties(Duration holdTtl) {

    public BookingProperties {
        if (holdTtl == null) {
            holdTtl = Duration.ofMinutes(5);
        }
    }
}
