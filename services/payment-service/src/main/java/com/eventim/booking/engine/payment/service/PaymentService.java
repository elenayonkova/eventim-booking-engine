package com.eventim.booking.engine.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
import com.eventim.booking.engine.payment.repository.PaymentRecord;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.repository.RefundRecord;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentResponse createPayment(PaymentRequest request, Long delayMs, String simulateFailure) {
        sleepIfRequested(delayMs);

        String fingerprint = fingerprint(request.paymentMethodToken());
        return paymentRepository.findPaymentByReservationIdForUpdate(request.reservationId())
                .map(existingPayment -> {
                    ensureSameIdempotencyPayload(existingPayment, request, fingerprint);
                    return toResponse(existingPayment);
                })
                .orElseGet(() -> {
                    boolean failed = isSimulatedFailure(simulateFailure);
                    PaymentStatus status = failed ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
                    String failureReason = failed ? "Simulated payment failure" : null;
                    PaymentRecord inserted = paymentRepository.insertPayment(
                            UUID.randomUUID(),
                            request.reservationId(),
                            request.amount(),
                            request.currency().toUpperCase(),
                            fingerprint,
                            status,
                            failureReason);
                    return toResponse(inserted);
                });
    }

    @Transactional
    public RefundResponse refund(RefundRequest request) {
        PaymentRecord payment = paymentRepository.findPaymentByReservationIdForUpdate(request.reservationId())
                .orElseThrow(() -> new NotFoundException("Payment not found for reservation: " + request.reservationId()));

        if (payment.status() == PaymentStatus.FAILED) {
            throw new ConflictException("Cannot refund a failed payment");
        }

        return paymentRepository.findRefundByReservationIdForUpdate(request.reservationId())
                .map(this::toResponse)
                .orElseGet(() -> {
                    RefundRecord refund = paymentRepository.insertRefund(
                            UUID.randomUUID(),
                            request.reservationId(),
                            payment.id(),
                            RefundStatus.SUCCEEDED);
                    paymentRepository.markPaymentRefunded(payment.id());
                    return toResponse(refund);
                });
    }

    private void ensureSameIdempotencyPayload(PaymentRecord existingPayment, PaymentRequest request, String fingerprint) {
        if (existingPayment.amount() != request.amount()
                || !existingPayment.currency().equalsIgnoreCase(request.currency())
                || !existingPayment.paymentMethodFingerprint().equals(fingerprint)) {
            throw new ConflictException("Payment already exists for reservation with different payment details");
        }
    }

    private PaymentResponse toResponse(PaymentRecord payment) {
        return new PaymentResponse(
                payment.id(),
                payment.reservationId(),
                payment.status(),
                payment.failureReason());
    }

    private RefundResponse toResponse(RefundRecord refund) {
        return new RefundResponse(
                refund.id(),
                refund.reservationId(),
                refund.paymentId(),
                refund.status());
    }

    private boolean isSimulatedFailure(String simulateFailure) {
        return simulateFailure != null
                && ("true".equalsIgnoreCase(simulateFailure)
                || "1".equals(simulateFailure)
                || "yes".equalsIgnoreCase(simulateFailure));
    }

    private void sleepIfRequested(Long delayMs) {
        if (delayMs == null || delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConflictException("Payment simulation delay was interrupted");
        }
    }

    private String fingerprint(String paymentMethodToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(paymentMethodToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
