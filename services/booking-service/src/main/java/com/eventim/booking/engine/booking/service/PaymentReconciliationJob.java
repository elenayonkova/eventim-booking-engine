package com.eventim.booking.engine.booking.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentReconciliationJob {

    private final BookingService bookingService;

    public PaymentReconciliationJob(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "${booking.payment-reconciliation-sweep-ms:30000}")
    public void reconcilePayments() {
        bookingService.reconcilePendingPayments();
    }
}
