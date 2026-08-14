package com.eventim.booking.engine.booking.service.checkout;

import java.util.UUID;

import com.eventim.booking.engine.booking.api.CheckoutResponse;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.repository.ReservationRow;

/**
 * Immutable handoff between transactional reservation changes and external
 * payment orchestration. The reservation status is the single source of truth
 * for the next operation.
 */
record CheckoutSnapshot(
        UUID reservationId,
        UUID paymentId,
        ReservationStatus status,
        long amount,
        String currency,
        String paymentMethodFingerprint,
        String failureReason
) {

    static CheckoutSnapshot from(ReservationRow reservation) {
        return new CheckoutSnapshot(
                reservation.id(),
                reservation.paymentId(),
                reservation.status(),
                reservation.checkoutAmount(),
                reservation.checkoutCurrency(),
                reservation.paymentMethodFingerprint(),
                reservation.paymentFailureReason());
    }

    CheckoutSnapshot withPaymentResult(
            UUID newPaymentId,
            ReservationStatus newStatus,
            String newFailureReason
    ) {
        return new CheckoutSnapshot(
                reservationId,
                newPaymentId,
                newStatus,
                amount,
                currency,
                paymentMethodFingerprint,
                newFailureReason);
    }

    CheckoutResponse toResponse() {
        return new CheckoutResponse(
                reservationId,
                paymentId,
                status,
                amount,
                currency,
                failureReason);
    }
}
