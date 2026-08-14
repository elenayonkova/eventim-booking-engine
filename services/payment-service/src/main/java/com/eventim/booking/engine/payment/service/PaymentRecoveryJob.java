package com.eventim.booking.engine.payment.service;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.payment.repository.PaymentRepository;

/**
 * Periodically marks abandoned processing payments as failed so callers can
 * reconcile requests interrupted before their completion transition.
 */
@Component
public class PaymentRecoveryJob {

    private final PaymentRepository paymentRepository;

    public PaymentRecoveryJob(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Scheduled(fixedDelayString = "${payment.recovery-sweep-ms:30000}")
    @Transactional
    public void failInterruptedPayments() {
        Duration timeout = Duration.ofMinutes(2);
        // FUTURE PROVIDER CALL: query the provider for stale payment and refund
        // outcomes outside a database transaction before applying recovered states.
        // The simulator can only mark interrupted local attempts as failed.
        paymentRepository.failStaleProcessingPayments(timeout);
        paymentRepository.finalizeFailedCancellationIntents();
        paymentRepository.failStaleProcessingRefunds(timeout);
    }
}
