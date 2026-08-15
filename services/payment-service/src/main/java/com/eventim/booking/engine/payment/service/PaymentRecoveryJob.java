package com.eventim.booking.engine.payment.service;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.payment.repository.PaymentRepository;

/**
 * Periodically marks abandoned provider operations as unresolved so callers do
 * not mistake an unknown external outcome for a failure or cancellation.
 */
@Component
public class PaymentRecoveryJob {

    private final PaymentRepository paymentRepository;

    public PaymentRecoveryJob(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Scheduled(fixedDelayString = "${payment.recovery-sweep-ms}")
    @Transactional
    public void recoverInterruptedOperations() {
        Duration timeout = Duration.ofMinutes(2);
        // A real provider integration can later reconcile UNKNOWN rows by the
        // durable payment ID. Until then, an unknown outcome must stay non-terminal.
        paymentRepository.markStalePaymentsUnknown(timeout);
        paymentRepository.failStaleProcessingRefunds(timeout);
    }
}
