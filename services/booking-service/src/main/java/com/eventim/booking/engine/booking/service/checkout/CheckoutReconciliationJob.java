package com.eventim.booking.engine.booking.service.checkout;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles checkout work that outlived the initiating HTTP request. Missing
 * payments expire and release their seats; durable payment and refund results
 * are applied idempotently.
 */
@Component
public class CheckoutReconciliationJob {

    private final CheckoutService checkoutService;

    public CheckoutReconciliationJob(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Scheduled(fixedDelayString = "${booking.payment-reconciliation-sweep-ms}")
    public void reconcilePayments() {
        checkoutService.reconcilePendingPayments();
    }

    @Scheduled(fixedDelayString = "${booking.refund-reconciliation-sweep-ms}")
    public void reconcileRefunds() {
        checkoutService.reconcileRequiredRefunds();
    }
}
