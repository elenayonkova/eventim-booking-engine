package com.eventim.booking.engine.booking.service.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.eventim.booking.engine.booking.api.CheckoutRequest;
import com.eventim.booking.engine.booking.api.CheckoutResponse;
import com.eventim.booking.engine.booking.payment.ChargePayment;
import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentSimulation;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.payment.RefundStatus;
import com.eventim.booking.engine.booking.repository.BookingRepository;
import com.eventim.booking.engine.booking.config.BookingProperties;
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

/**
 * Coordinates checkout and external payment calls. Reservation status is the
 * only dispatcher; all local transitions are owned by {@link ReservationCheckout}.
 */
@Service
public class CheckoutService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CheckoutService.class);

    private final BookingRepository bookingRepository;
    private final PaymentGateway paymentGateway;
    private final ReservationCheckout reservationCheckout;
    private final BookingProperties bookingProperties;

    public CheckoutService(
            BookingRepository bookingRepository,
            PaymentGateway paymentGateway,
            ReservationCheckout reservationCheckout,
            BookingProperties bookingProperties
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentGateway = paymentGateway;
        this.reservationCheckout = reservationCheckout;
        this.bookingProperties = bookingProperties;
    }

    public CheckoutResponse completeCheckout(
            CheckoutRequest request,
            Long simulatedDelayMs,
            String simulatedFailure
    ) {
        PaymentSimulation simulation = new PaymentSimulation(simulatedDelayMs, simulatedFailure);
        CheckoutSnapshot checkout = reservationCheckout.beginCheckout(
                request.reservationId(),
                tokenDigest(request.paymentMethodToken()));

        return continuePreparedCheckout(checkout, request, simulation);
    }

    public void reconcileRequiredRefunds() {
        for (UUID reservationId : bookingRepository.findRefundRequiredReservationIds()) {
            try {
                reconcileRequiredRefund(reservationId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not reconcile refund for reservation {}", reservationId, exception);
            }
        }
    }

    public void reconcilePendingPayments() {
        for (UUID reservationId : bookingRepository.findPaymentPendingReservationIds(
                bookingProperties.paymentMissingTimeout())) {
            try {
                reconcilePendingPayment(reservationId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not reconcile payment for reservation {}", reservationId, exception);
            }
        }
    }

    private CheckoutResponse continuePreparedCheckout(
            CheckoutSnapshot checkout,
            CheckoutRequest request,
            PaymentSimulation simulation
    ) {
        return switch (checkout.status()) {
            case PAYMENT_PENDING -> chargeAndApplyPayment(request, simulation, checkout);
            case REFUND_REQUIRED -> refundAndFinalize(checkout);
            case BOOKED, PAYMENT_FAILED, REFUNDED -> checkout.toResponse();
            case EXPIRED -> throw reservationExpired(checkout.reservationId());
            case HELD -> throw new IllegalStateException(
                    "Checkout preparation returned state " + checkout.status());
        };
    }

    private CheckoutResponse chargeAndApplyPayment(
            CheckoutRequest request,
            PaymentSimulation simulation,
            CheckoutSnapshot checkout
    ) {
        PaymentResult payment = paymentGateway.charge(new ChargePayment(
                request.reservationId(),
                checkout.amount(),
                checkout.currency(),
                request.paymentMethodToken(),
                simulation));
        return applyPaymentResult(checkout, payment);
    }

    private void reconcileRequiredRefund(UUID reservationId) {
        CheckoutSnapshot checkout = reservationCheckout.loadRefundRequiredCheckout(reservationId);
        if (checkout != null) {
            refundAndFinalize(checkout);
        }
    }

    private void reconcilePendingPayment(UUID reservationId) {
        CheckoutSnapshot checkout = reservationCheckout.loadPaymentPendingCheckout(reservationId);
        if (checkout == null) {
            return;
        }

        Optional<PaymentResult> payment = paymentGateway.findPayment(reservationId);
        if (payment.isEmpty()) {
            reservationCheckout.expireMissingPayment(checkout);
            return;
        }
        applyPaymentResult(checkout, payment.get());
    }

    private CheckoutResponse applyPaymentResult(
            CheckoutSnapshot checkout,
            PaymentResult payment
    ) {
        CheckoutSnapshot updated = reservationCheckout.applyPaymentResult(checkout, payment);
        return switch (updated.status()) {
            case REFUND_REQUIRED -> refundAndFinalize(updated);
            case PAYMENT_PENDING, BOOKED, PAYMENT_FAILED, REFUNDED -> updated.toResponse();
            case HELD, EXPIRED -> throw new IllegalStateException(
                    "Payment result produced state " + updated.status());
        };
    }

    private CheckoutResponse refundAndFinalize(CheckoutSnapshot checkout) {
        RefundResult refund = paymentGateway.refund(checkout.reservationId());
        if (refund.status() != RefundStatus.SUCCEEDED) {
            throw new ExternalServiceException("Refund is still unresolved; retry checkout safely");
        }

        return reservationCheckout.markRefunded(checkout, refund).toResponse();
    }

    private ConflictException reservationExpired(UUID reservationId) {
        return new ConflictException("Reservation has expired: " + reservationId);
    }

    private String tokenDigest(String paymentMethodToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(paymentMethodToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
