package com.eventim.booking.engine.booking.service.checkout;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentReconciliationJob {

    private final CheckoutService checkoutService;

    public PaymentReconciliationJob(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Scheduled(fixedDelayString = "${booking.payment-reconciliation-sweep-ms:30000}")
    public void reconcilePayments() {
        checkoutService.reconcilePendingPayments();
    }
}
