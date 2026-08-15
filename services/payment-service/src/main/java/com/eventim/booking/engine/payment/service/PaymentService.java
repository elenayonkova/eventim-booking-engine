package com.eventim.booking.engine.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.provider.PaymentProvider;
import com.eventim.booking.engine.payment.provider.PaymentProvider.OperationOutcome;
import com.eventim.booking.engine.payment.provider.PaymentProvider.Simulation;

/**
 * Coordinates payment requests across durable local state and the external
 * provider boundary. Local transitions are delegated to
 * {@link PaymentTransactions}; provider calls never hold database transactions.
 */
@Service
public class PaymentService {

    public static final long MAX_SIMULATED_DELAY_MS = 60_000;

    private final PaymentTransactions paymentTransactions;
    private final PaymentProvider paymentProvider;

    public PaymentService(
            PaymentTransactions paymentTransactions,
            PaymentProvider paymentProvider
    ) {
        this.paymentTransactions = paymentTransactions;
        this.paymentProvider = paymentProvider;
    }

    public PaymentResponse createPayment(PaymentRequest request, Long delayMs, String simulateFailure) {
        Simulation simulation = simulation(delayMs, simulateFailure);
        String requestTokenDigest = tokenDigest(request.paymentMethodToken());
        ProviderStep<PaymentResponse> step = paymentTransactions.startPayment(
                request,
                requestTokenDigest);

        if (!step.providerCallRequired()) {
            return step.response();
        }

        // SIMULATED PROVIDER CALL: the durable payment ID is the idempotency key.
        PaymentRequest providerRequest = step.providerRequest();
        String providerTokenDigest = tokenDigest(providerRequest.paymentMethodToken());
        OperationOutcome outcome = paymentProvider.charge(
                step.response().paymentId(),
                providerRequest,
                simulation);
        return paymentTransactions.completePayment(
                providerRequest,
                providerTokenDigest,
                step.attempt(),
                outcome);
    }

    public PaymentResponse getPayment(UUID reservationId) {
        return paymentTransactions.findPayment(reservationId);
    }

    public PaymentResponse recoverPayment(UUID reservationId) {
        ProviderStep<PaymentResponse> step = paymentTransactions.startPaymentRecovery(reservationId);
        if (!step.providerCallRequired()) {
            return step.response();
        }

        PaymentRequest providerRequest = step.providerRequest();
        OperationOutcome outcome = paymentProvider.charge(
                step.response().paymentId(),
                providerRequest,
                simulation(null, null));
        return paymentTransactions.completePayment(
                providerRequest,
                tokenDigest(providerRequest.paymentMethodToken()),
                step.attempt(),
                outcome);
    }

    public RefundResponse createRefund(RefundRequest request, Long delayMs, String simulateFailure) {
        Simulation simulation = simulation(delayMs, simulateFailure);
        RefundStep step = paymentTransactions.startRefund(request);

        if (!step.providerCallRequired()) {
            return step.response();
        }

        // SIMULATED PROVIDER CALL: the durable refund ID is the idempotency key.
        OperationOutcome outcome = paymentProvider.refund(
                step.response().refundId(),
                step.response().paymentId(),
                request,
                simulation);
        return paymentTransactions.completeRefund(
                request.reservationId(),
                step.response().refundId(),
                step.attempt(),
                outcome);
    }

    private void validateSimulationDelay(Long delayMs) {
        if (delayMs != null && (delayMs < 0 || delayMs > MAX_SIMULATED_DELAY_MS)) {
            throw new IllegalArgumentException(
                    "Simulation delay must be between 0 and " + MAX_SIMULATED_DELAY_MS + " milliseconds");
        }
    }

    private Simulation simulation(Long delayMs, String simulateFailure) {
        Simulation simulation = new Simulation(delayMs, simulateFailure);
        validateSimulationDelay(delayMs);
        return simulation;
    }

    private String tokenDigest(String paymentMethodToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(paymentMethodToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

}
