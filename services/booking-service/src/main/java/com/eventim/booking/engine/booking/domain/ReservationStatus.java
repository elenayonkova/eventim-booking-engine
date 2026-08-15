package com.eventim.booking.engine.booking.domain;

public enum ReservationStatus {
    HELD,
    PAYMENT_PENDING,
    BOOKED,
    EXPIRED,
    PAYMENT_FAILED,
    REFUND_REQUIRED,
    REFUNDED;

    public boolean hasCheckoutResponse() {
        return switch (this) {
            case BOOKED, PAYMENT_FAILED, REFUNDED -> true;
            case HELD, PAYMENT_PENDING, EXPIRED, REFUND_REQUIRED -> false;
        };
    }
}
