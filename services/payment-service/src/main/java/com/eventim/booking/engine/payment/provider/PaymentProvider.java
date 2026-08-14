package com.eventim.booking.engine.payment.provider;

import java.util.UUID;

import com.eventim.booking.engine.payment.api.PaymentCancellationRequest;
import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.RefundRequest;

/**
 * Boundary for calls to the external payment provider. Implementations perform
 * network I/O without a database transaction; durable local state is prepared
 * before these methods are called and finalized from their outcomes afterward.
 */
public interface PaymentProvider {

    OperationOutcome charge(
            UUID paymentId,
            PaymentRequest request,
            Simulation simulation
    );

    OperationOutcome refund(
            UUID refundId,
            UUID paymentId,
            RefundRequest request,
            Simulation simulation
    );

    CancellationOutcome cancel(
            UUID paymentId,
            PaymentCancellationRequest request,
            Simulation simulation
    );

    enum OperationOutcome {
        SUCCEEDED,
        FAILED
    }

    enum CancellationOutcome {
        CANCELLED,
        PAYMENT_SUCCEEDED,
        PENDING
    }

    record Simulation(Long delayMs, String failure) {

        public boolean requested() {
            return delayMs != null || (failure != null && !failure.isBlank());
        }

        public boolean failureRequested() {
            return failure != null
                    && ("true".equalsIgnoreCase(failure)
                    || "1".equals(failure)
                    || "yes".equalsIgnoreCase(failure));
        }
    }
}
