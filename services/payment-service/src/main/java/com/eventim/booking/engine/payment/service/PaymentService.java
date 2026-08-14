package com.eventim.booking.engine.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.PaymentCancellationRequest;
import com.eventim.booking.engine.payment.api.PaymentCancellationResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.provider.PaymentProvider;
import com.eventim.booking.engine.payment.provider.PaymentProvider.CancellationOutcome;
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
    private final boolean simulationEnabled;

    public PaymentService(
            PaymentTransactions paymentTransactions,
            PaymentProvider paymentProvider,
            @Value("${payment.simulation-enabled:true}") boolean simulationEnabled
    ) {
        this.paymentTransactions = paymentTransactions;
        this.paymentProvider = paymentProvider;
        this.simulationEnabled = simulationEnabled;
    }

    public PaymentResponse createPayment(PaymentRequest request, Long delayMs, String simulateFailure) {
        Simulation simulation = simulation(delayMs, simulateFailure);
        String fingerprint = fingerprint(request.paymentMethodToken());
        UUID idempotencyKey = idempotencyKeyFor(request);
        ProviderStep<PaymentResponse> step = paymentTransactions.startPayment(
                request,
                idempotencyKey,
                fingerprint);

        if (!step.providerCallRequired()) {
            return step.response();
        }

        // REAL PROVIDER CALL: a production PaymentProvider submits the charge
        // here using the durable payment ID as its idempotency key.
        OperationOutcome outcome = paymentProvider.charge(
                step.response().paymentId(),
                request,
                simulation);
        return paymentTransactions.completePayment(request, idempotencyKey, fingerprint, outcome);
    }

    public PaymentResponse getPayment(UUID reservationId) {
        return paymentTransactions.getPayment(reservationId);
    }

    public PaymentCancellationResponse cancelPayment(
            PaymentCancellationRequest request,
            Long delayMs,
            String simulateFailure
    ) {
        Simulation simulation = simulation(delayMs, simulateFailure);
        ProviderStep<PaymentCancellationResponse> step = paymentTransactions.startCancellation(
                request.reservationId());

        if (!step.providerCallRequired()) {
            return step.response();
        }

        PaymentResponse payment = step.response().payment();
        if (payment == null) {
            throw new IllegalStateException("Provider cancellation requires a payment");
        }

        // REAL PROVIDER CALL: a production PaymentProvider asks the provider to
        // cancel or resolve the processing charge here, outside the transaction.
        CancellationOutcome outcome = paymentProvider.cancel(
                payment.paymentId(),
                request,
                simulation);
        return paymentTransactions.completeCancellation(
                request.reservationId(),
                payment.paymentId(),
                outcome);
    }

    public RefundResponse refund(RefundRequest request, Long delayMs, String simulateFailure) {
        Simulation simulation = simulation(delayMs, simulateFailure);
        ProviderStep<RefundResponse> step = paymentTransactions.startRefund(request);

        if (!step.providerCallRequired()) {
            return step.response();
        }

        // REAL PROVIDER CALL: a production PaymentProvider submits the refund
        // here using the durable refund ID as its idempotency key.
        OperationOutcome outcome = paymentProvider.refund(
                step.response().refundId(),
                step.response().paymentId(),
                request,
                simulation);
        return paymentTransactions.completeRefund(
                request.reservationId(),
                step.response().refundId(),
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
        if (!simulationEnabled && simulation.requested()) {
            throw new IllegalArgumentException("Payment simulation is disabled");
        }
        validateSimulationDelay(delayMs);
        return simulation;
    }

    private UUID idempotencyKeyFor(PaymentRequest request) {
        return request.reservationId();
    }

    private String fingerprint(String paymentMethodToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(paymentMethodToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

}
