package com.eventim.booking.engine.payment.api;

import static com.eventim.booking.engine.payment.service.PaymentService.MAX_SIMULATED_DELAY_MS;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @RequestHeader(name = "X-Simulate-Delay-Ms", required = false)
            @Min(0) @Max(MAX_SIMULATED_DELAY_MS) Long delayMs,
            @RequestHeader(name = "X-Simulate-Failure", required = false) String simulateFailure
    ) {
        return paymentService.createPayment(request, delayMs, simulateFailure);
    }

    @PostMapping("/payments/cancellations")
    public PaymentCancellationResponse cancelPayment(
            @Valid @RequestBody PaymentCancellationRequest request
    ) {
        return paymentService.cancelPayment(request);
    }

    @PostMapping("/refunds")
    public RefundResponse refund(@Valid @RequestBody RefundRequest request) {
        return paymentService.refund(request);
    }

    @GetMapping("/payments/by-reservation/{reservationId}")
    public PaymentResponse getPayment(@PathVariable java.util.UUID reservationId) {
        return paymentService.getPayment(reservationId);
    }
}
