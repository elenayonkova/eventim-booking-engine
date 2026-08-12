package com.eventim.booking.engine.booking.domain;

public enum ReservationStatus {
    HELD,
    PAYMENT_PENDING,
    BOOKED,
    EXPIRED,
    PAYMENT_FAILED,
    REFUND_REQUIRED,
    REFUNDED
}
