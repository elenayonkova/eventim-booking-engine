package com.eventim.booking.engine.payment.provider;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.service.ConflictException;

/**
 * In-process payment provider used by this sample application. It replaces
 * external charge and refund calls with configurable latency and deterministic
 * outcomes while preserving the real provider boundary.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    @Override
    public OperationOutcome charge(
            UUID paymentId,
            PaymentRequest request,
            Simulation simulation
    ) {
        simulateLatency(simulation);
        return operationOutcome(simulation);
    }

    @Override
    public OperationOutcome refund(
            UUID refundId,
            UUID paymentId,
            RefundRequest request,
            Simulation simulation
    ) {
        simulateLatency(simulation);
        return operationOutcome(simulation);
    }

    private OperationOutcome operationOutcome(Simulation simulation) {
        return simulation.failureRequested()
                ? OperationOutcome.FAILED
                : OperationOutcome.SUCCEEDED;
    }

    private void simulateLatency(Simulation simulation) {
        if (simulation.delayMs() == null || simulation.delayMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(simulation.delayMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConflictException("Payment provider simulation was interrupted");
        }
    }
}
