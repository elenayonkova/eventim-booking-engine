package com.eventim.booking.engine.booking.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.eventim.booking.engine.booking.api.CheckoutRequest;
import com.eventim.booking.engine.booking.api.CheckoutResponse;
import com.eventim.booking.engine.booking.api.CreateReservationRequest;
import com.eventim.booking.engine.booking.api.ReservationResponse;
import com.eventim.booking.engine.booking.api.SeatAvailabilityResponse;
import com.eventim.booking.engine.booking.config.BookingProperties;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.domain.SeatStatus;
import com.eventim.booking.engine.booking.payment.ChargePayment;
import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentSimulation;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.payment.RefundStatus;
import com.eventim.booking.engine.booking.repository.BookingRepository;
import com.eventim.booking.engine.booking.repository.ReservationRow;
import com.eventim.booking.engine.booking.repository.ReservationSeatRow;

@Service
public class BookingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingProperties bookingProperties;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;

    public BookingService(
            BookingRepository bookingRepository,
            BookingProperties bookingProperties,
            PaymentGateway paymentGateway,
            PlatformTransactionManager transactionManager
    ) {
        this.bookingRepository = bookingRepository;
        this.bookingProperties = bookingProperties;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public SeatAvailabilityResponse getSeats(String eventId) {
        bookingRepository.releaseExpiredHoldsForEvent(eventId);

        if (!bookingRepository.eventExists(eventId)) {
            throw new NotFoundException("Event not found: " + eventId);
        }

        List<SeatAvailabilityResponse.Seat> seats = bookingRepository.findSeats(eventId).stream()
                .map(row -> new SeatAvailabilityResponse.Seat(
                        row.seatLabel(),
                        row.status(),
                        row.expiresAt()))
                .toList();

        return new SeatAvailabilityResponse(eventId, seats);
    }

    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request) {
        BookingRepository.ReservationInsertResult result = bookingRepository.createReservation(
                request.eventId(),
                request.seatIds(),
                bookingProperties.holdTtl());

        return new ReservationResponse(
                result.reservationId(),
                result.eventId(),
                result.seatIds(),
                ReservationStatus.HELD,
                result.expiresAt());
    }

    public CheckoutResponse checkout(
            CheckoutRequest request,
            Long simulatedDelayMs,
            String simulatedFailure) {
        PaymentSimulation simulation = new PaymentSimulation(simulatedDelayMs, simulatedFailure);
        String paymentMethodFingerprint = fingerprint(request.paymentMethodToken());
        CheckoutWork work = Objects.requireNonNull(withLockedReservation(
                request.reservationId(),
                reservation -> beginCheckout(reservation, paymentMethodFingerprint)));

        return switch (work.action()) {
            case RETURN -> work.toResponse();
            case EXPIRED -> throw reservationExpired(request.reservationId());
            case REFUND -> refundAndFinalize(work);
            case CHARGE -> chargeAndApplyPayment(request, simulation, work);
        };
    }

    private CheckoutResponse chargeAndApplyPayment(
            CheckoutRequest request,
            PaymentSimulation simulation,
            CheckoutWork work
    ) {
        PaymentResult payment = paymentGateway.charge(new ChargePayment(
                request.reservationId(),
                work.amount(),
                work.currency(),
                request.paymentMethodToken(),
                simulation));

        return applyPaymentResult(work, payment);
    }

    public void reconcilePendingPayments() {
        for (UUID reservationId : bookingRepository.findPaymentPendingReservationIds()) {
            try {
                reconcilePendingPayment(reservationId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not reconcile payment for reservation {}", reservationId, exception);
            }
        }
        for (UUID reservationId : bookingRepository.findRefundRequiredReservationIds()) {
            try {
                reconcileRequiredRefund(reservationId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not reconcile refund for reservation {}", reservationId, exception);
            }
        }
    }

    private void reconcilePendingPayment(UUID reservationId) {
        CheckoutWork work = withLockedReservation(reservationId, reservation -> {
            if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
                return null;
            }
            return CheckoutWork.charge(reservation);
        });
        if (work == null) {
            return;
        }

        Optional<PaymentResult> payment = paymentGateway.find(reservationId);
        if (payment.isEmpty()) {
            failAbandonedPaymentIfTimedOut(reservationId);
            return;
        }

        applyPaymentResult(work, payment.get());
    }

    private CheckoutResponse applyPaymentResult(
            CheckoutWork work,
            PaymentResult payment
    ) {
        if (!payment.reservationId().equals(work.reservationId())) {
            throw new ExternalServiceException("Payment service returned a result for another reservation");
        }
        if (!paymentMatchesCheckout(work, payment)) {
            return resolveMismatchedPayment(work, payment);
        }

        return switch (payment.status()) {
            case PROCESSING -> recordProcessingPayment(work, payment);
            case SUCCEEDED -> completeSuccessfulPayment(work, payment);
            case FAILED -> completeFailedPayment(work, payment);
            case REFUNDED -> recordAlreadyRefunded(work, payment);
        };
    }

    private CheckoutResponse resolveMismatchedPayment(
            CheckoutWork work,
            PaymentResult payment
    ) {
        String reason = "Payment payload did not match the stored checkout";
        return switch (payment.status()) {
            case PROCESSING -> recordProcessingPayment(work, payment);
            case SUCCEEDED -> refundMismatchedPayment(work, payment, reason);
            case FAILED -> completeFailedPayment(
                    work,
                    new PaymentResult(
                            payment.paymentId(),
                            payment.reservationId(),
                            payment.amount(),
                            payment.currency(),
                            payment.paymentMethodFingerprint(),
                            payment.status(),
                            reason));
            case REFUNDED -> recordAlreadyRefunded(work, payment);
        };
    }

    private CheckoutResponse refundMismatchedPayment(
            CheckoutWork work,
            PaymentResult payment,
            String reason
    ) {
        CheckoutWork current = withStoredCheckout(work, reservation -> {
            if (reservation.status() == ReservationStatus.REFUNDED) {
                return CheckoutWork.terminal(reservation);
            }
            if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
                return CheckoutWork.refund(reservation);
            }
            if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
                throw new ConflictException(
                        "Mismatched payment cannot be refunded from state " + reservation.status());
            }

            bookingRepository.markRefundRequired(reservation.id(), payment.paymentId(), reason);
            bookingRepository.releaseHeldSeats(reservation.id());
            return new CheckoutWork(
                    CheckoutAction.REFUND,
                    reservation.id(),
                    payment.paymentId(),
                    ReservationStatus.REFUND_REQUIRED,
                    work.amount(),
                    work.currency(),
                    work.paymentMethodFingerprint(),
                    reason);
        });

        return completeNonChargeAction(current);
    }

    private void failAbandonedPaymentIfTimedOut(UUID reservationId) {
        transactionTemplate.executeWithoutResult(status -> {
            ReservationRow reservation = bookingRepository.lockReservation(reservationId);
            if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
                return;
            }
            if (reservation.checkoutStartedAt() == null
                    || reservation.checkoutStartedAt().plus(bookingProperties.paymentPendingTimeout())
                    .isAfter(bookingRepository.databaseNow())) {
                return;
            }

            bookingRepository.releaseHeldSeats(reservation.id());
            bookingRepository.markPaymentFailed(
                    reservation.id(),
                    reservation.paymentId(),
                    "No payment was accepted before the reconciliation timeout");
        });
    }

    private void reconcileRequiredRefund(UUID reservationId) {
        CheckoutWork work = withLockedReservation(reservationId, reservation -> {
            if (reservation.status() != ReservationStatus.REFUND_REQUIRED) {
                return null;
            }
            return CheckoutWork.refund(reservation);
        });
        if (work != null) {
            refundAndFinalize(work);
        }
    }

    private CheckoutWork beginCheckout(ReservationRow reservation, String paymentMethodFingerprint) {
        return switch (reservation.status()) {
            case HELD -> beginHeldCheckout(reservation, paymentMethodFingerprint);
            case PAYMENT_PENDING -> {
                ensureSameCheckoutPayload(reservation, paymentMethodFingerprint);
                requireReservationOwnsSeats(reservation.id(), SeatStatus.HELD);
                yield CheckoutWork.charge(reservation);
            }
            case BOOKED, PAYMENT_FAILED, REFUNDED -> {
                ensureSameCheckoutPayload(reservation, paymentMethodFingerprint);
                yield CheckoutWork.terminal(reservation);
            }
            case REFUND_REQUIRED -> {
                ensureSameCheckoutPayload(reservation, paymentMethodFingerprint);
                yield CheckoutWork.refund(reservation);
            }
            case EXPIRED -> CheckoutWork.expired(reservation.id());
        };
    }

    private CheckoutWork beginHeldCheckout(ReservationRow reservation, String paymentMethodFingerprint) {
        if (!reservation.expiresAt().isAfter(bookingRepository.databaseNow())) {
            bookingRepository.expireHeldReservation(reservation.id());
            return CheckoutWork.expired(reservation.id());
        }

        List<ReservationSeatRow> seats = requireReservationOwnsSeats(reservation.id(), SeatStatus.HELD);
        Pricing pricing = calculatePricing(seats);
        bookingRepository.markPaymentPending(
                reservation.id(),
                pricing.amount(),
                pricing.currency(),
                paymentMethodFingerprint);

        return new CheckoutWork(
                CheckoutAction.CHARGE,
                reservation.id(),
                null,
                ReservationStatus.PAYMENT_PENDING,
                pricing.amount(),
                pricing.currency(),
                paymentMethodFingerprint,
                null);
    }

    private CheckoutResponse completeSuccessfulPayment(
            CheckoutWork work,
            PaymentResult payment
    ) {
        CheckoutWork completed = withStoredCheckout(work, reservation -> {
            if (reservation.status() == ReservationStatus.BOOKED) {
                return CheckoutWork.terminal(reservation);
            }
            if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
                return CheckoutWork.refund(reservation);
            }
            if (reservation.status() == ReservationStatus.REFUNDED) {
                return CheckoutWork.terminal(reservation);
            }
            if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
                throw new ConflictException(
                        "Reservation cannot be booked from state " + reservation.status());
            }

            List<ReservationSeatRow> seats = bookingRepository.lockReservationSeats(reservation.id());
            if (!reservationOwnsEverySeat(reservation.id(), seats, SeatStatus.HELD)) {
                bookingRepository.markRefundRequired(
                        reservation.id(),
                        payment.paymentId(),
                        "Paid reservation no longer owns every held seat");
                bookingRepository.releaseHeldSeats(reservation.id());
                return new CheckoutWork(
                        CheckoutAction.REFUND,
                        reservation.id(),
                        payment.paymentId(),
                        ReservationStatus.REFUND_REQUIRED,
                        work.amount(),
                        work.currency(),
                        work.paymentMethodFingerprint(),
                        "Booking could not be finalized; refund required");
            }

            bookingRepository.bookSeats(reservation.id());
            bookingRepository.markBooked(reservation.id(), payment.paymentId());
            return new CheckoutWork(
                    CheckoutAction.RETURN,
                    reservation.id(),
                    payment.paymentId(),
                    ReservationStatus.BOOKED,
                    work.amount(),
                    work.currency(),
                    work.paymentMethodFingerprint(),
                    null);
        });

        return completeNonChargeAction(completed);
    }

    private CheckoutResponse recordProcessingPayment(
            CheckoutWork work,
            PaymentResult payment
    ) {
        CheckoutWork current = withStoredCheckout(work, reservation -> {
            if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
                bookingRepository.recordProcessingPayment(reservation.id(), payment.paymentId());
                return new CheckoutWork(
                        CheckoutAction.RETURN,
                        reservation.id(),
                        payment.paymentId(),
                        ReservationStatus.PAYMENT_PENDING,
                        work.amount(),
                        work.currency(),
                        work.paymentMethodFingerprint(),
                        null);
            }
            if (reservation.status() == ReservationStatus.REFUND_REQUIRED) {
                return CheckoutWork.refund(reservation);
            }
            if (reservation.status() == ReservationStatus.BOOKED
                    || reservation.status() == ReservationStatus.PAYMENT_FAILED
                    || reservation.status() == ReservationStatus.REFUNDED) {
                return CheckoutWork.terminal(reservation);
            }
            throw new ConflictException(
                    "Processing payment cannot be applied from state " + reservation.status());
        });

        return completeNonChargeAction(current);
    }

    private CheckoutResponse completeFailedPayment(
            CheckoutWork work,
            PaymentResult payment
    ) {
        CheckoutWork completed = withStoredCheckout(work, reservation -> {
            if (reservation.status() == ReservationStatus.PAYMENT_FAILED
                    || reservation.status() == ReservationStatus.BOOKED
                    || reservation.status() == ReservationStatus.REFUNDED) {
                return CheckoutWork.terminal(reservation);
            }
            if (reservation.status() != ReservationStatus.PAYMENT_PENDING) {
                throw new ConflictException(
                        "Payment failure cannot be applied from state " + reservation.status());
            }

            bookingRepository.releaseHeldSeats(reservation.id());
            bookingRepository.markPaymentFailed(
                    reservation.id(),
                    payment.paymentId(),
                    payment.failureReason());
            return new CheckoutWork(
                    CheckoutAction.RETURN,
                    reservation.id(),
                    payment.paymentId(),
                    ReservationStatus.PAYMENT_FAILED,
                    work.amount(),
                    work.currency(),
                    work.paymentMethodFingerprint(),
                    payment.failureReason());
        });
        return completed.toResponse();
    }

    private CheckoutResponse recordAlreadyRefunded(
            CheckoutWork work,
            PaymentResult payment
    ) {
        CheckoutWork completed = withStoredCheckout(work, reservation -> {
            if (reservation.status() == ReservationStatus.REFUNDED) {
                return CheckoutWork.terminal(reservation);
            }
            if (reservation.status() == ReservationStatus.PAYMENT_PENDING) {
                bookingRepository.markRefundRequired(
                        reservation.id(),
                        payment.paymentId(),
                        "Payment was already refunded before booking completed");
                bookingRepository.releaseHeldSeats(reservation.id());
                bookingRepository.markRefunded(reservation.id());
                return new CheckoutWork(
                        CheckoutAction.RETURN,
                        reservation.id(),
                        payment.paymentId(),
                        ReservationStatus.REFUNDED,
                        work.amount(),
                        work.currency(),
                        work.paymentMethodFingerprint(),
                        "Payment was refunded");
            }
            throw new ConflictException(
                    "Refunded payment cannot be applied from state " + reservation.status());
        });
        return completed.toResponse();
    }

    private CheckoutResponse refundAndFinalize(CheckoutWork work) {
        RefundResult refund = paymentGateway.refund(work.reservationId());
        if (refund.status() != RefundStatus.SUCCEEDED) {
            throw new ExternalServiceException("Refund is still unresolved; retry checkout safely");
        }

        CheckoutWork completed = Objects.requireNonNull(withLockedReservation(work.reservationId(), reservation -> {
            if (reservation.status() == ReservationStatus.REFUNDED) {
                return CheckoutWork.terminal(reservation);
            }
            if (reservation.status() != ReservationStatus.REFUND_REQUIRED) {
                throw new ConflictException(
                        "Refund cannot be applied from state " + reservation.status());
            }
            bookingRepository.releaseHeldSeats(reservation.id());
            bookingRepository.markRefunded(reservation.id());
            return new CheckoutWork(
                    CheckoutAction.RETURN,
                    reservation.id(),
                    refund.paymentId(),
                    ReservationStatus.REFUNDED,
                    reservation.checkoutAmount(),
                    reservation.checkoutCurrency(),
                    reservation.paymentMethodFingerprint(),
                    reservation.paymentFailureReason());
        }));
        return completed.toResponse();
    }

    private CheckoutWork withLockedReservation(
            UUID reservationId,
            Function<ReservationRow, CheckoutWork> transition
    ) {
        return transactionTemplate.execute(status ->
                transition.apply(bookingRepository.lockReservation(reservationId)));
    }

    private CheckoutWork withStoredCheckout(
            CheckoutWork work,
            Function<ReservationRow, CheckoutWork> transition
    ) {
        return Objects.requireNonNull(withLockedReservation(work.reservationId(), reservation -> {
            ensureStoredPricing(reservation, work);
            return transition.apply(reservation);
        }));
    }

    private CheckoutResponse completeNonChargeAction(CheckoutWork work) {
        return switch (work.action()) {
            case RETURN -> work.toResponse();
            case REFUND -> refundAndFinalize(work);
            case EXPIRED -> throw reservationExpired(work.reservationId());
            case CHARGE -> throw new IllegalStateException("Checkout action still requires payment");
        };
    }

    private ConflictException reservationExpired(UUID reservationId) {
        return new ConflictException("Reservation has expired: " + reservationId);
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
        return !seats.isEmpty() && seats.stream().allMatch(seat ->
                seat.status() == requiredStatus
                        && reservationId.equals(seat.currentReservationId()));
    }

    private Pricing calculatePricing(List<ReservationSeatRow> seats) {
        String currency = seats.get(0).currency();
        if (seats.stream().anyMatch(seat -> !currency.equals(seat.currency()))) {
            throw new ConflictException("A reservation cannot contain seats in different currencies");
        }
        long amount = seats.stream()
                .mapToLong(ReservationSeatRow::priceAmount)
                .reduce(0L, Math::addExact);
        return new Pricing(amount, currency);
    }

    private void ensureSameCheckoutPayload(ReservationRow reservation, String paymentMethodFingerprint) {
        if (reservation.checkoutAmount() == null
                || reservation.checkoutCurrency() == null
                || reservation.paymentMethodFingerprint() == null) {
            throw new ConflictException("Reservation has incomplete checkout state: " + reservation.id());
        }
        if (!reservation.paymentMethodFingerprint().equals(paymentMethodFingerprint)) {
            throw new ConflictException(
                    "Checkout already exists for reservation with different payment details");
        }
    }

    private void ensureStoredPricing(ReservationRow reservation, CheckoutWork work) {
        if (reservation.checkoutAmount() == null
                || reservation.checkoutAmount() != work.amount()
                || !Objects.equals(reservation.checkoutCurrency(), work.currency())) {
            throw new ConflictException("Reservation checkout price changed unexpectedly");
        }
    }

    private boolean paymentMatchesCheckout(
            CheckoutWork work,
            PaymentResult payment
    ) {
        return payment.amount() == work.amount()
                && payment.currency() != null
                && payment.currency().equalsIgnoreCase(work.currency())
                && Objects.equals(
                        payment.paymentMethodFingerprint(),
                        work.paymentMethodFingerprint());
    }

    private String fingerprint(String paymentMethodToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(paymentMethodToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private enum CheckoutAction {
        CHARGE,
        REFUND,
        RETURN,
        EXPIRED
    }

    private record Pricing(long amount, String currency) {
    }

    private record CheckoutWork(
            CheckoutAction action,
            UUID reservationId,
            UUID paymentId,
            ReservationStatus reservationStatus,
            long amount,
            String currency,
            String paymentMethodFingerprint,
            String failureReason
    ) {
        static CheckoutWork charge(ReservationRow reservation) {
            return fromReservation(CheckoutAction.CHARGE, reservation);
        }

        static CheckoutWork refund(ReservationRow reservation) {
            return fromReservation(CheckoutAction.REFUND, reservation);
        }

        static CheckoutWork terminal(ReservationRow reservation) {
            return fromReservation(CheckoutAction.RETURN, reservation);
        }

        static CheckoutWork expired(UUID reservationId) {
            return new CheckoutWork(
                    CheckoutAction.EXPIRED,
                    reservationId,
                    null,
                    ReservationStatus.EXPIRED,
                    0,
                    null,
                    null,
                    "Reservation expired");
        }

        private static CheckoutWork fromReservation(
                CheckoutAction action,
                ReservationRow reservation
        ) {
            if (reservation.checkoutAmount() == null || reservation.checkoutCurrency() == null) {
                throw new ConflictException(
                        "Reservation has incomplete checkout state: " + reservation.id());
            }
            return new CheckoutWork(
                    action,
                    reservation.id(),
                    reservation.paymentId(),
                    reservation.status(),
                    reservation.checkoutAmount(),
                    reservation.checkoutCurrency(),
                    reservation.paymentMethodFingerprint(),
                    reservation.paymentFailureReason());
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
}
