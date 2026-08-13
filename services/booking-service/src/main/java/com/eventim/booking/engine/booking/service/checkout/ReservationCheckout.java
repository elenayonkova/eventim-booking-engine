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
 * External payment and refund calls are deliberately handled by {@link CheckoutService}
 * so that no database transaction is held while waiting for another service.
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
    public CheckoutStep beginCheckout(UUID reservationId, String paymentMethodFingerprint) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);

        switch (reservation.status()) {
            case HELD:
                return beginHeldCheckout(reservation, paymentMethodFingerprint);
            case PAYMENT_PENDING:
                ensureSameCheckoutPayload(reservation, paymentMethodFingerprint);
                requireReservationOwnsSeats(reservation.id(), SeatStatus.HELD);
                return CheckoutStep.charge(reservation);
            case BOOKED:
            case PAYMENT_FAILED:
            case REFUNDED:
                ensureSameCheckoutPayload(reservation, paymentMethodFingerprint);
                return CheckoutStep.terminal(reservation);
            case REFUND_REQUIRED:
                ensureSameCheckoutPayload(reservation, paymentMethodFingerprint);
                return CheckoutStep.refund(reservation);
            case EXPIRED:
                return CheckoutStep.expired(reservation.id());
            default:
                throw new IllegalStateException("Unknown reservation state: " + reservation.status());
        }
    }

    @Transactional
    public CheckoutStep loadPaymentPendingCheckout(UUID reservationId) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);
        if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
            return CheckoutStep.charge(reservation);
        }
        return null;
    }

    @Transactional
    public CheckoutStep loadRefundRequiredCheckout(UUID reservationId) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);
        if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            return CheckoutStep.refund(reservation);
        }
        return null;
    }

    @Transactional
    public CheckoutStep loadTimedOutPaymentPendingCheckout(
            UUID reservationId,
            Duration pendingTimeout
    ) {
        ReservationRow reservation = bookingRepository.lockReservation(reservationId);
        if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
            return null;
        }
        if (reservation.checkoutStartedAt() == null) {
            return null;
        }
        if (reservation.checkoutStartedAt().plus(pendingTimeout).isAfter(bookingRepository.databaseNow())) {
            return null;
        }

        return CheckoutStep.charge(reservation);
    }

    @Transactional
    public void failPaymentAfterCancellation(CheckoutStep checkout) {
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
    public CheckoutStep applyPaymentResult(CheckoutStep checkout, PaymentResult payment) {
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
    public CheckoutStep markRefunded(CheckoutStep checkout, RefundResult refund) {
        ReservationRow reservation = bookingRepository.lockReservation(checkout.reservationId());
        validateRefund(checkout, reservation, refund);
        if (reservation.status() == ReservationStatus.REFUNDED) {
            return CheckoutStep.terminal(reservation);
        }
        if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            bookingRepository.markRefundedAndReleaseSeats(reservation.id());
            return CheckoutStep.refunded(reservation, refund.paymentId());
        }

        throw new ConflictException(
                "Refund cannot be applied from state " + reservation.status());
    }

    private CheckoutStep beginHeldCheckout(
            ReservationRow reservation,
            String paymentMethodFingerprint
    ) {
        if (!reservation.expiresAt().isAfter(bookingRepository.databaseNow())) {
            bookingRepository.expireHeldReservation(reservation.id());
            return CheckoutStep.expired(reservation.id());
        }

        List<ReservationSeatRow> seats = requireReservationOwnsSeats(
                reservation.id(),
                SeatStatus.HELD);
        Pricing pricing = calculatePricing(seats);
        bookingRepository.markPaymentPending(
                reservation.id(),
                pricing.amount(),
                pricing.currency(),
                paymentMethodFingerprint);

        return CheckoutStep.startedCharge(
                reservation.id(),
                pricing.amount(),
                pricing.currency(),
                paymentMethodFingerprint);
    }

    private CheckoutStep applyProcessingPayment(
            ReservationRow reservation,
            CheckoutStep checkout,
            PaymentResult payment
    ) {
        if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
            bookingRepository.recordProcessingPayment(reservation.id(), payment.paymentId());
            return checkout.asResponse(payment.paymentId(), ReservationStatus.PAYMENT_PENDING, null);
        }
        if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            return CheckoutStep.refund(reservation);
        }
        if (reservation.status().hasCheckoutResponse()) {
            return CheckoutStep.terminal(reservation);
        }

        throw new ConflictException(
                "Processing payment cannot be applied from state " + reservation.status());
    }

    private CheckoutStep applySuccessfulPayment(
            ReservationRow reservation,
            CheckoutStep checkout,
            PaymentResult payment
    ) {
        if (reservation.status() == ReservationStatus.BOOKED
                || reservation.status() == ReservationStatus.REFUNDED) {
            return CheckoutStep.terminal(reservation);
        }
        if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            return CheckoutStep.refund(reservation);
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
            return checkout.asRefundRequired(
                    payment.paymentId(),
                    "Booking could not be finalized; refund required");
        }

        bookingRepository.bookSeatsAndMarkBooked(reservation.id(), payment.paymentId());
        return checkout.asResponse(payment.paymentId(), ReservationStatus.BOOKED, null);
    }

    private CheckoutStep applyFailedPayment(
            ReservationRow reservation,
            CheckoutStep checkout,
            PaymentResult payment,
            String failureReason
    ) {
        if (reservation.status().hasCheckoutResponse()) {
            return CheckoutStep.terminal(reservation);
        }
        if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
            bookingRepository.markPaymentFailedAndReleaseSeats(
                    reservation.id(),
                    payment.paymentId(),
                    failureReason);
            return checkout.asResponse(
                    payment.paymentId(),
                    ReservationStatus.PAYMENT_FAILED,
                    failureReason);
        }

        throw new ConflictException(
                "Payment failure cannot be applied from state " + reservation.status());
    }

    private CheckoutStep applyAlreadyRefundedPayment(
            ReservationRow reservation,
            CheckoutStep checkout,
            PaymentResult payment
    ) {
        if (reservation.status() == ReservationStatus.REFUNDED) {
            return CheckoutStep.terminal(reservation);
        }
        if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
            String reason = "Payment was already refunded before booking completed";
            bookingRepository.markRefundRequiredAndReleaseSeats(
                    reservation.id(),
                    payment.paymentId(),
                    reason);
            bookingRepository.markRefunded(reservation.id());
            return checkout.asResponse(payment.paymentId(), ReservationStatus.REFUNDED, reason);
        }

        throw new ConflictException(
                "Refunded payment cannot be applied from state " + reservation.status());
    }

    private CheckoutStep applyMismatchedPaymentForRefund(
            ReservationRow reservation,
            CheckoutStep checkout,
            PaymentResult payment,
            String reason
    ) {
        if (reservation.status() == ReservationStatus.REFUNDED) {
            return CheckoutStep.terminal(reservation);
        }
        if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
            return CheckoutStep.refund(reservation);
        }
        if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
            bookingRepository.markRefundRequiredAndReleaseSeats(
                    reservation.id(),
                    payment.paymentId(),
                    reason);
            return checkout.asRefundRequired(payment.paymentId(), reason);
        }

        throw new ConflictException(
                "Mismatched payment cannot be refunded from state " + reservation.status());
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
            if (seat.status() != requiredStatus) {
                return false;
            }
            if (!reservationId.equals(seat.currentReservationId())) {
                return false;
            }
        }
        return true;
    }

    private Pricing calculatePricing(List<ReservationSeatRow> seats) {
        String currency = seats.get(0).currency();
        long amount = 0L;
        for (ReservationSeatRow seat : seats) {
            if (!currency.equals(seat.currency())) {
                throw new ConflictException(
                        "A reservation cannot contain seats in different currencies");
            }
            amount = Math.addExact(amount, seat.priceAmount());
        }
        return new Pricing(amount, currency);
    }

    private void ensureSameCheckoutPayload(
            ReservationRow reservation,
            String paymentMethodFingerprint
    ) {
        if (reservation.checkoutAmount() == null
                || reservation.checkoutCurrency() == null
                || reservation.paymentMethodFingerprint() == null) {
            throw new ConflictException(
                    "Reservation has incomplete checkout state: " + reservation.id());
        }
        if (!reservation.paymentMethodFingerprint().equals(paymentMethodFingerprint)) {
            throw new ConflictException(
                    "Checkout already exists for reservation with different payment details");
        }
    }

    private void ensureStoredCheckout(ReservationRow reservation, CheckoutStep checkout) {
        if (reservation.checkoutAmount() == null
                || reservation.paymentMethodFingerprint() == null
                || reservation.checkoutAmount() != checkout.amount()
                || !Objects.equals(reservation.checkoutCurrency(), checkout.currency())
                || !Objects.equals(
                        reservation.paymentMethodFingerprint(),
                        checkout.paymentMethodFingerprint())) {
            throw new ConflictException("Reservation checkout price changed unexpectedly");
        }
    }

    private void validatePaymentReservation(CheckoutStep checkout, PaymentResult payment) {
        if (!payment.reservationId().equals(checkout.reservationId())) {
            throw new ExternalServiceException(
                    "Payment service returned a result for another reservation");
        }
    }

    private void validateRefund(
            CheckoutStep checkout,
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

    private boolean paymentMatchesCheckout(CheckoutStep checkout, PaymentResult payment) {
        return payment.amount() == checkout.amount()
                && payment.currency() != null
                && payment.currency().equalsIgnoreCase(checkout.currency())
                && Objects.equals(
                        payment.paymentMethodFingerprint(),
                        checkout.paymentMethodFingerprint());
    }

    private record Pricing(long amount, String currency) {
    }
}
