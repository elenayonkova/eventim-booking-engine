package com.eventim.booking.engine.booking.service.checkout;

import java.util.UUID;

import com.eventim.booking.engine.booking.api.CheckoutResponse;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.repository.ReservationRow;
import com.eventim.booking.engine.booking.service.ConflictException;

record CheckoutStep(
        Action action,
        UUID reservationId,
        UUID paymentId,
        ReservationStatus reservationStatus,
        long amount,
        String currency,
        String paymentMethodFingerprint,
        String failureReason
) {

    enum Action {
        CHARGE,
        REFUND,
        RETURN,
        EXPIRED
    }

    static CheckoutStep charge(ReservationRow reservation) {
        return fromReservation(Action.CHARGE, reservation);
    }

    static CheckoutStep refund(ReservationRow reservation) {
        return fromReservation(Action.REFUND, reservation);
    }

    static CheckoutStep terminal(ReservationRow reservation) {
        return fromReservation(Action.RETURN, reservation);
    }

    static CheckoutStep expired(UUID reservationId) {
        return new CheckoutStep(
                Action.EXPIRED,
                reservationId,
                null,
                ReservationStatus.EXPIRED,
                0,
                null,
                null,
                "Reservation expired");
    }

    static CheckoutStep startedCharge(
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodFingerprint
    ) {
        return new CheckoutStep(
                Action.CHARGE,
                reservationId,
                null,
                ReservationStatus.PAYMENT_PENDING,
                amount,
                currency,
                paymentMethodFingerprint,
                null);
    }

    static CheckoutStep refunded(ReservationRow reservation, UUID paymentId) {
        return new CheckoutStep(
                Action.RETURN,
                reservation.id(),
                paymentId,
                ReservationStatus.REFUNDED,
                reservation.checkoutAmount(),
                reservation.checkoutCurrency(),
                reservation.paymentMethodFingerprint(),
                reservation.paymentFailureReason());
    }

    private static CheckoutStep fromReservation(Action action, ReservationRow reservation) {
        if (reservation.checkoutAmount() == null
                || reservation.checkoutCurrency() == null
                || reservation.paymentMethodFingerprint() == null) {
            throw new ConflictException(
                    "Reservation has incomplete checkout state: " + reservation.id());
        }
        return new CheckoutStep(
                action,
                reservation.id(),
                reservation.paymentId(),
                reservation.status(),
                reservation.checkoutAmount(),
                reservation.checkoutCurrency(),
                reservation.paymentMethodFingerprint(),
                reservation.paymentFailureReason());
    }

    CheckoutStep asResponse(
            UUID newPaymentId,
            ReservationStatus newStatus,
            String newFailureReason
    ) {
        return new CheckoutStep(
                Action.RETURN,
                reservationId,
                newPaymentId,
                newStatus,
                amount,
                currency,
                paymentMethodFingerprint,
                newFailureReason);
    }

    CheckoutStep asRefundRequired(UUID newPaymentId, String newFailureReason) {
        return new CheckoutStep(
                Action.REFUND,
                reservationId,
                newPaymentId,
                ReservationStatus.REFUND_REQUIRED,
                amount,
                currency,
                paymentMethodFingerprint,
                newFailureReason);
    }

    CheckoutResponse toResponse() {
        return new CheckoutResponse(
                reservationId,
                paymentId,
                reservationStatus,
                amount,
                currency,
                failureReason);
    }
}
