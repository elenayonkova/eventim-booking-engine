package com.eventim.booking.engine.booking.service.checkout;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.domain.SeatStatus;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.repository.BookingRepository;
import com.eventim.booking.engine.booking.repository.ReservationRow;
import com.eventim.booking.engine.booking.repository.ReservationSeatRow;
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

/**
 * Owns every local, transactional state transition in the checkout workflow.
 * External payment and refund calls are deliberately handled by
 * {@link CheckoutService} so no database transaction is held during network I/O.
 */
@Component
public class ReservationCheckout {

    private static final String MISMATCHED_PAYMENT_REASON =
            "Payment payload did not match the stored checkout";

    private final BookingRepository bookingRepository;

    public ReservationCheckout(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Transactional
    public CheckoutSnapshot beginCheckout(UUID reservationId, String paymentMethodFingerprint) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);

        switch (reservation.status()) {
            case HELD:
                return beginHeldCheckout(reservation, paymentMethodFingerprint);
            case PAYMENT_PENDING:
                ensureSamePaymentMethod(reservation, paymentMethodFingerprint);
                requireReservationOwnsSeats(reservation.id(), SeatStatus.HELD);
                return CheckoutSnapshot.from(reservation);
            case BOOKED:
            case PAYMENT_FAILED:
            case REFUND_REQUIRED:
            case REFUNDED:
                ensureSamePaymentMethod(reservation, paymentMethodFingerprint);
                return CheckoutSnapshot.from(reservation);
            case EXPIRED:
                return CheckoutSnapshot.from(reservation);
            default:
                throw new IllegalStateException("Unknown reservation state: " + reservation.status());
        }
    }

    @Transactional
    public CheckoutSnapshot loadPaymentPendingCheckout(UUID reservationId) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            return null;
        }
        bookingRepository.touchReconciliationAttempt(
                reservation.id(),
                ReservationStatus.PAYMENT_PENDING);
        return CheckoutSnapshot.from(reservation);
    }

    @Transactional
    public CheckoutSnapshot loadRefundRequiredCheckout(UUID reservationId) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);
        if (reservation.status() != ReservationStatus.REFUND_REQUIRED) {
            return null;
        }
        bookingRepository.touchReconciliationAttempt(
                reservation.id(),
                ReservationStatus.REFUND_REQUIRED);
        return CheckoutSnapshot.from(reservation);
    }

    @Transactional
    public CheckoutSnapshot loadTimedOutPaymentPendingCheckout(
            UUID reservationId,
            Duration pendingTimeout
    ) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            return null;
        }
        if (reservation.checkoutStartedAt().plus(pendingTimeout)
                .isAfter(bookingRepository.databaseNow())) {
            return null;
        }
        return CheckoutSnapshot.from(reservation);
    }

    @Transactional
    public void failPaymentAfterCancellation(CheckoutSnapshot checkout) {
        ReservationRow reservation = bookingRepository.lockReservation(checkout.reservationId());
        ensureStoredCheckout(reservation, checkout);
        if (reservation.status().hasCheckoutResponse()) {
            return;
        }
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            throw new ConflictException(
                    "Cancelled payment cannot be applied from state " + reservation.status());
        }

        bookingRepository.markPaymentFailedAndReleaseSeats(
                reservation.id(),
                reservation.paymentId(),
                "Payment was cancelled after the reconciliation timeout");
    }

    @Transactional
    public CheckoutSnapshot applyPaymentResult(CheckoutSnapshot checkout, PaymentResult payment) {
        validatePaymentReservation(checkout, payment);

        ReservationRow reservation = bookingRepository.lockReservation(checkout.reservationId());
        ensureStoredCheckout(reservation, checkout);
        boolean paymentMatches = paymentMatchesCheckout(checkout, payment);

        switch (payment.status()) {
            case PROCESSING:
                return applyProcessingPayment(reservation, checkout, payment);
            case SUCCEEDED:
                if (paymentMatches) {
                    return applySuccessfulPayment(reservation, checkout, payment);
                }
                return applyMismatchedPaymentForRefund(
                        reservation,
                        checkout,
                        payment,
                        MISMATCHED_PAYMENT_REASON);
            case FAILED:
                String failureReason = paymentMatches
                        ? payment.failureReason()
                        : MISMATCHED_PAYMENT_REASON;
                return applyFailedPayment(reservation, checkout, payment, failureReason);
            case REFUNDED:
                return applyAlreadyRefundedPayment(reservation, checkout, payment);
            default:
                throw new IllegalStateException("Unknown payment state: " + payment.status());
        }
    }

    @Transactional
    public CheckoutSnapshot markRefunded(CheckoutSnapshot checkout, RefundResult refund) {
        ReservationRow reservation = bookingRepository.lockReservation(checkout.reservationId());
        validateRefund(checkout, reservation, refund);
        if (reservation.status() == ReservationStatus.REFUNDED) {
            return CheckoutSnapshot.from(reservation);
        }
        if (reservation.status() != ReservationStatus.REFUND_REQUIRED) {
            throw new ConflictException(
                    "Refund cannot be applied from state " + reservation.status());
        }

        bookingRepository.markRefundedAndReleaseSeats(reservation.id());
        return checkout.withPaymentResult(
                refund.paymentId(),
                ReservationStatus.REFUNDED,
                reservation.paymentFailureReason());
    }

    private CheckoutSnapshot beginHeldCheckout(
            ReservationRow reservation,
            String paymentMethodFingerprint
    ) {
        if (!reservation.expiresAt().isAfter(bookingRepository.databaseNow())) {
            bookingRepository.expireHeldReservation(reservation.id());
            return snapshot(
                    reservation,
                    null,
                    ReservationStatus.EXPIRED,
                    null,
                    "Reservation expired");
        }

        requireReservationOwnsSeats(reservation.id(), SeatStatus.HELD);
        bookingRepository.markPaymentPending(reservation.id(), paymentMethodFingerprint);
        return snapshot(
                reservation,
                null,
                ReservationStatus.PAYMENT_PENDING,
                paymentMethodFingerprint,
                null);
    }

    private CheckoutSnapshot applyProcessingPayment(
            ReservationRow reservation,
            CheckoutSnapshot checkout,
            PaymentResult payment
    ) {
        if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
            bookingRepository.recordProcessingPayment(reservation.id(), payment.paymentId());
            return checkout.withPaymentResult(
                    payment.paymentId(),
                    ReservationStatus.PAYMENT_PENDING,
                    null);
        }
        if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            return CheckoutSnapshot.from(reservation);
        }
        if (reservation.status().hasCheckoutResponse()) {
            return CheckoutSnapshot.from(reservation);
        }

        throw new ConflictException(
                "Processing payment cannot be applied from state " + reservation.status());
    }

    private CheckoutSnapshot applySuccessfulPayment(
            ReservationRow reservation,
            CheckoutSnapshot checkout,
            PaymentResult payment
    ) {
        if (reservation.status() == ReservationStatus.BOOKED
                || reservation.status() == ReservationStatus.REFUNDED
                || reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            return CheckoutSnapshot.from(reservation);
        }
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            throw new ConflictException(
                    "Reservation cannot be booked from state " + reservation.status());
        }

        List<ReservationSeatRow> seats = bookingRepository.lockReservationSeats(reservation.id());
        if (!reservationOwnsEverySeat(reservation.id(), seats, SeatStatus.HELD)) {
            bookingRepository.markRefundRequiredAndReleaseSeats(
                    reservation.id(),
                    payment.paymentId(),
                    "Paid reservation no longer owns every held seat");
            return checkout.withPaymentResult(
                    payment.paymentId(),
                    ReservationStatus.REFUND_REQUIRED,
                    "Booking could not be finalized; refund required");
        }

        bookingRepository.bookSeatsAndMarkBooked(reservation.id(), payment.paymentId());
        return checkout.withPaymentResult(payment.paymentId(), ReservationStatus.BOOKED, null);
    }

    private CheckoutSnapshot applyFailedPayment(
            ReservationRow reservation,
            CheckoutSnapshot checkout,
            PaymentResult payment,
            String failureReason
    ) {
        if (reservation.status().hasCheckoutResponse()) {
            return CheckoutSnapshot.from(reservation);
        }
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            throw new ConflictException(
                    "Payment failure cannot be applied from state " + reservation.status());
        }

        bookingRepository.markPaymentFailedAndReleaseSeats(
                reservation.id(),
                payment.paymentId(),
                failureReason);
        return checkout.withPaymentResult(
                payment.paymentId(),
                ReservationStatus.PAYMENT_FAILED,
                failureReason);
    }

    private CheckoutSnapshot applyAlreadyRefundedPayment(
            ReservationRow reservation,
            CheckoutSnapshot checkout,
            PaymentResult payment
    ) {
        if (reservation.status() == ReservationStatus.REFUNDED) {
            return CheckoutSnapshot.from(reservation);
        }
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            throw new ConflictException(
                    "Refunded payment cannot be applied from state " + reservation.status());
        }

        String reason = "Payment was already refunded before booking completed";
        bookingRepository.markRefundRequiredAndReleaseSeats(
                reservation.id(),
                payment.paymentId(),
                reason);
        bookingRepository.markRefunded(reservation.id());
        return checkout.withPaymentResult(payment.paymentId(), ReservationStatus.REFUNDED, reason);
    }

    private CheckoutSnapshot applyMismatchedPaymentForRefund(
            ReservationRow reservation,
            CheckoutSnapshot checkout,
            PaymentResult payment,
            String reason
    ) {
        if (reservation.status() == ReservationStatus.REFUNDED
                || reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            return CheckoutSnapshot.from(reservation);
        }
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            throw new ConflictException(
                    "Mismatched payment cannot be refunded from state " + reservation.status());
        }

        bookingRepository.markRefundRequiredAndReleaseSeats(
                reservation.id(),
                payment.paymentId(),
                reason);
        return checkout.withPaymentResult(
                payment.paymentId(),
                ReservationStatus.REFUND_REQUIRED,
                reason);
    }

    private List<ReservationSeatRow> requireReservationOwnsSeats(
            UUID reservationId,
            SeatStatus requiredStatus
    ) {
        List<ReservationSeatRow> seats = bookingRepository.lockReservationSeats(reservationId);
        if (!reservationOwnsEverySeat(reservationId, seats, requiredStatus)) {
            throw new ConflictException(
                    "Reservation does not own every seat in state " + requiredStatus);
        }
        return seats;
    }

    private boolean reservationOwnsEverySeat(
            UUID reservationId,
            List<ReservationSeatRow> seats,
            SeatStatus requiredStatus
    ) {
        if (seats.isEmpty()) {
            return false;
        }

        for (ReservationSeatRow seat : seats) {
            if (seat.status() != requiredStatus
                    || !reservationId.equals(seat.currentReservationId())) {
                return false;
            }
        }
        return true;
    }

    private void ensureSamePaymentMethod(
            ReservationRow reservation,
            String paymentMethodFingerprint
    ) {
        if (!reservation.paymentMethodFingerprint().equals(paymentMethodFingerprint)) {
            throw new ConflictException(
                    "Checkout already exists for reservation with different payment details");
        }
    }

    private void ensureStoredCheckout(
            ReservationRow reservation,
            CheckoutSnapshot checkout
    ) {
        if (reservation.checkoutAmount() != checkout.amount()
                || !reservation.checkoutCurrency().equals(checkout.currency())
                || !reservation.paymentMethodFingerprint().equals(
                        checkout.paymentMethodFingerprint())) {
            throw new ConflictException("Reservation checkout price changed unexpectedly");
        }
    }

    private void validatePaymentReservation(CheckoutSnapshot checkout, PaymentResult payment) {
        if (payment.paymentId() == null
                || payment.reservationId() == null
                || payment.status() == null) {
            throw new ExternalServiceException(
                    "Payment service returned an incomplete payment result");
        }
        if (!checkout.reservationId().equals(payment.reservationId())) {
            throw new ExternalServiceException(
                    "Payment service returned a result for another reservation");
        }
    }

    private void validateRefund(
            CheckoutSnapshot checkout,
            ReservationRow reservation,
            RefundResult refund
    ) {
        if (refund.refundId() == null
                || refund.reservationId() == null
                || refund.paymentId() == null) {
            throw new ExternalServiceException(
                    "Payment service returned an incomplete refund result");
        }
        if (!refund.reservationId().equals(checkout.reservationId())
                || !refund.reservationId().equals(reservation.id())) {
            throw new ExternalServiceException(
                    "Payment service returned a refund for another reservation");
        }
        if (!refund.paymentId().equals(checkout.paymentId())
                || !refund.paymentId().equals(reservation.paymentId())) {
            throw new ExternalServiceException(
                    "Payment service returned a refund for another payment");
        }
    }

    private boolean paymentMatchesCheckout(CheckoutSnapshot checkout, PaymentResult payment) {
        return payment.amount() == checkout.amount()
                && checkout.currency().equalsIgnoreCase(payment.currency())
                && Objects.equals(
                        payment.paymentMethodFingerprint(),
                        checkout.paymentMethodFingerprint());
    }

    private CheckoutSnapshot snapshot(
            ReservationRow reservation,
            UUID paymentId,
            ReservationStatus status,
            String paymentMethodFingerprint,
            String failureReason
    ) {
        return new CheckoutSnapshot(
                reservation.id(),
                paymentId,
                status,
                reservation.checkoutAmount(),
                reservation.checkoutCurrency(),
                paymentMethodFingerprint,
                failureReason);
    }
}
