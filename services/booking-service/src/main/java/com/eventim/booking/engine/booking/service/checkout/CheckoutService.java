package com.eventim.booking.engine.booking.service.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.eventim.booking.engine.booking.api.CheckoutRequest;
import com.eventim.booking.engine.booking.api.CheckoutResponse;
import com.eventim.booking.engine.booking.config.BookingProperties;
import com.eventim.booking.engine.booking.payment.ChargePayment;
import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.PaymentCancellationResult;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentSimulation;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.payment.RefundStatus;
import com.eventim.booking.engine.booking.repository.BookingRepository;
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

/**
 * Coordinates the checkout workflow and external payment calls. Local state
 * transitions are delegated to {@link ReservationCheckout}.
 */
@Service
public class CheckoutService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CheckoutService.class);

    private final BookingRepository bookingRepository;
    private final BookingProperties bookingProperties;
    private final PaymentGateway paymentGateway;
    private final ReservationCheckout reservationCheckout;

    public CheckoutService(
            BookingRepository bookingRepository,
            BookingProperties bookingProperties,
            PaymentGateway paymentGateway,
            ReservationCheckout reservationCheckout
    ) {
        this.bookingRepository = bookingRepository;
        this.bookingProperties = bookingProperties;
        this.paymentGateway = paymentGateway;
        this.reservationCheckout = reservationCheckout;
    }

    public CheckoutResponse checkout(
            CheckoutRequest request,
            Long simulatedDelayMs,
            String simulatedFailure
    ) {
        PaymentSimulation simulation = new PaymentSimulation(simulatedDelayMs, simulatedFailure);
        String paymentMethodFingerprint = fingerprint(request.paymentMethodToken());
        CheckoutStep checkout = reservationCheckout.beginCheckout(
                request.reservationId(),
                paymentMethodFingerprint);

        return performCheckoutAction(checkout, request, simulation);
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

    private CheckoutResponse performCheckoutAction(
            CheckoutStep checkout,
            CheckoutRequest request,
            PaymentSimulation simulation
    ) {
        if (checkout.action() == CheckoutStep.Action.RETURN) {
            return checkout.toResponse();
        }
        if (checkout.action() == CheckoutStep.Action.EXPIRED) {
            throw reservationExpired(request.reservationId());
        }
        if (checkout.action() == CheckoutStep.Action.REFUND) {
            return refundAndFinalize(checkout);
        }

        return chargeAndApplyPayment(request, simulation, checkout);
    }

    private CheckoutResponse chargeAndApplyPayment(
            CheckoutRequest request,
            PaymentSimulation simulation,
            CheckoutStep checkout
    ) {
        PaymentResult payment = paymentGateway.charge(new ChargePayment(
                request.reservationId(),
                checkout.amount(),
                checkout.currency(),
                request.paymentMethodToken(),
                simulation));

        return applyPaymentResult(checkout, payment);
    }

    private void reconcilePendingPayment(UUID reservationId) {
        CheckoutStep checkout = reservationCheckout.loadPaymentPendingCheckout(reservationId);
        if (checkout == null) {
            return;
        }

        Optional<PaymentResult> payment = paymentGateway.find(reservationId);
        if (payment.isEmpty()) {
            CheckoutStep timedOutCheckout = reservationCheckout.loadTimedOutPaymentPendingCheckout(
                    reservationId,
                    bookingProperties.paymentPendingTimeout());
            if (timedOutCheckout == null) {
                return;
            }

            PaymentCancellationResult cancellation = paymentGateway.cancel(reservationId);
            if (!reservationId.equals(cancellation.reservationId())) {
                throw new ExternalServiceException(
                        "Payment service cancelled another reservation");
            }
            if (cancellation.payment() == null) {
                reservationCheckout.failPaymentAfterCancellation(timedOutCheckout);
                return;
            }

            applyPaymentResult(timedOutCheckout, cancellation.payment());
            return;
        }

        applyPaymentResult(checkout, payment.get());
    }

    private void reconcileRequiredRefund(UUID reservationId) {
        CheckoutStep checkout = reservationCheckout.loadRefundRequiredCheckout(reservationId);
        if (checkout != null) {
            refundAndFinalize(checkout);
        }
    }

    private CheckoutResponse applyPaymentResult(CheckoutStep checkout, PaymentResult payment) {
        CheckoutStep updated = reservationCheckout.applyPaymentResult(checkout, payment);
        return completeNonChargeAction(updated);
    }

    private CheckoutResponse refundAndFinalize(CheckoutStep checkout) {
        RefundResult refund = paymentGateway.refund(checkout.reservationId());
        if (refund.status() != RefundStatus.SUCCEEDED) {
            throw new ExternalServiceException("Refund is still unresolved; retry checkout safely");
        }

        CheckoutStep completed = reservationCheckout.markRefunded(checkout, refund);
        return completed.toResponse();
    }

    private CheckoutResponse completeNonChargeAction(CheckoutStep checkout) {
        if (checkout.action() == CheckoutStep.Action.RETURN) {
            return checkout.toResponse();
        }
        if (checkout.action() == CheckoutStep.Action.REFUND) {
            return refundAndFinalize(checkout);
        }
        if (checkout.action() == CheckoutStep.Action.EXPIRED) {
            throw reservationExpired(checkout.reservationId());
        }
        throw new IllegalStateException("Checkout action still requires payment");
    }

    private ConflictException reservationExpired(UUID reservationId) {
        return new ConflictException("Reservation has expired: " + reservationId);
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
}
