package com.eventim.booking.engine.booking.service.checkout;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules recovery of unresolved payment and refund work. The actual
 * reconciliation workflow is delegated to {@link CheckoutService}.
 */
@Component
public class PaymentReconciliationJob {

    private final CheckoutService checkoutService;

    public PaymentReconciliationJob(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Scheduled(fixedDelayString = "${booking.payment-reconciliation-sweep-ms}")
    public void reconcilePayments() {
        checkoutService.reconcilePendingPayments();
    }
}
