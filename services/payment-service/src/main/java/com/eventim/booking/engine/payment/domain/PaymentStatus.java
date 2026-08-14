package com.eventim.booking.engine.payment.domain;

public enum PaymentStatus {
    PROCESSING,
    CANCELLATION_PENDING,
    CANCELLED,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
