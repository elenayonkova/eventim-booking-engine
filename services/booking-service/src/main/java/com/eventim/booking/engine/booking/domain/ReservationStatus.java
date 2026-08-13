package com.eventim.booking.engine.booking.domain;

public enum ReservationStatus {
    HELD,
    PAYMENT_PENDING,
    BOOKED,
    EXPIRED,
    PAYMENT_FAILED,
    REFUND_REQUIRED,
    REFUNDED;

    public boolean isTerminal() {
        switch (this) {
            case BOOKED:
            case EXPIRED:
            case PAYMENT_FAILED:
            case REFUNDED:
                return true;
            default:
                return false;
        }
    }

    public boolean hasCheckoutResponse() {
        switch (this) {
            case BOOKED:
            case PAYMENT_FAILED:
            case REFUNDED:
                return true;
            default:
                return false;
        }
    }
}
