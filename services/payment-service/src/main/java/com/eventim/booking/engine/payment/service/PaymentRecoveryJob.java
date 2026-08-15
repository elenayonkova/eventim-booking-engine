package com.eventim.booking.engine.payment.service;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eventim.booking.engine.payment.repository.PaymentRepository;

/**
 * Periodically recovers interrupted idempotent provider calls. Stale payments
 * are retried automatically, while stale refunds are made eligible for the
 * booking service's compensation retry.
 */
@Component
public class PaymentRecoveryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentRecoveryJob.class);

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final Duration providerAttemptTimeout;

    public PaymentRecoveryJob(
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            @Value("${payment.provider-attempt-timeout}") Duration providerAttemptTimeout
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.providerAttemptTimeout = providerAttemptTimeout;
    }

    @Scheduled(fixedDelayString = "${payment.recovery-sweep-ms}")
    public void recoverInterruptedOperations() {
        paymentRepository.failStaleProcessingRefunds(providerAttemptTimeout);
        for (UUID reservationId
                : paymentRepository.findStaleProcessingReservationIds(providerAttemptTimeout)) {
            try {
                paymentService.recoverPayment(reservationId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not recover payment for reservation {}", reservationId, exception);
            }
        }
    }
}
