package com.eventim.booking.engine.payment.service;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.payment.repository.PaymentRepository;

@Component
public class PaymentRecoveryJob {

    private final PaymentRepository paymentRepository;

    public PaymentRecoveryJob(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Scheduled(fixedDelayString = "${payment.recovery-sweep-ms:30000}")
    @Transactional
    public void failInterruptedPayments() {
        paymentRepository.failStaleProcessingPayments(Duration.ofMinutes(2));
    }
}
