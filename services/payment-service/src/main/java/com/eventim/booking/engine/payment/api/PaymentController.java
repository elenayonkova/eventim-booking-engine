package com.eventim.booking.engine.payment.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eventim.booking.engine.payment.service.PaymentService;

@RestController
@RequestMapping("/v1")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/payments")
    public PaymentResponse createPayment(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(name = "X-Simulate-Delay-Ms", required = false) Long delayMs,
            @RequestHeader(name = "X-Simulate-Failure", required = false) String simulateFailure
    ) {
        return paymentService.createPayment(request, delayMs, simulateFailure);
    }

    @PostMapping("/refunds")
    public RefundResponse refund(@Valid @RequestBody RefundRequest request) {
        return paymentService.refund(request);
    }
}
