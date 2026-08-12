package com.eventim.booking.engine.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    public static final long MAX_SIMULATED_DELAY_MS = 60_000;

    private final PaymentRepository paymentRepository;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository, PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PaymentResponse createPayment(PaymentRequest request, Long delayMs, String simulateFailure) {
        validateSimulationDelay(delayMs);
        String fingerprint = fingerprint(request.paymentMethodToken());
        PaymentStart start = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Optional<PaymentRecord> inserted = paymentRepository.insertPaymentIfAbsent(
                    UUID.randomUUID(),
                    request.reservationId(),
                    request.amount(),
                    request.currency().toUpperCase(),
                    fingerprint,
                    PaymentStatus.PROCESSING,
                    null);
            if (inserted.isPresent()) {
                return new PaymentStart(inserted.get(), true);
            }

            PaymentRecord existingPayment = paymentRepository.findPaymentByReservationId(request.reservationId())
                    .orElseThrow(() -> new IllegalStateException("Existing payment disappeared"));
            ensureSameIdempotencyPayload(existingPayment, request, fingerprint);
            return new PaymentStart(existingPayment, false);
        }));

        if (!start.created()) {
            return toResponse(start.payment());
        }

        // Provider latency happens after the durable PROCESSING record is
        // committed, without holding a transaction or database connection.
        sleepIfRequested(delayMs);
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            PaymentRecord current = paymentRepository.findPaymentByReservationId(request.reservationId())
                    .orElseThrow(() -> new IllegalStateException("Processing payment disappeared"));
            ensureSameIdempotencyPayload(current, request, fingerprint);
            if (current.status() != PaymentStatus.PROCESSING) {
                return toResponse(current);
            }

            boolean failed = isSimulatedFailure(simulateFailure);
            PaymentStatus finalStatus = failed ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
            String failureReason = failed ? "Simulated payment failure" : null;
            return toResponse(paymentRepository.completeProcessingPayment(
                    current.id(),
                    finalStatus,
                    failureReason));
        }));
    }

    public PaymentResponse getPayment(UUID reservationId) {
        return paymentRepository.findPaymentByReservationId(reservationId)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException(
                        "Payment not found for reservation: " + reservationId));
    }

    public RefundResponse refund(RefundRequest request) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            PaymentRecord payment = paymentRepository.findPaymentByReservationIdForUpdate(request.reservationId())
                    .orElseThrow(() -> new NotFoundException(
                            "Payment not found for reservation: " + request.reservationId()));

            if (payment.status() == PaymentStatus.FAILED || payment.status() == PaymentStatus.PROCESSING) {
                throw new ConflictException("Only a successful payment can be refunded");
            }

            return paymentRepository.findRefundByReservationId(request.reservationId())
                    .map(this::toResponse)
                    .orElseGet(() -> {
                        RefundRecord refund = paymentRepository.insertRefundIfAbsent(
                                UUID.randomUUID(),
                                request.reservationId(),
                                payment.id(),
                                RefundStatus.SUCCEEDED)
                                .orElseGet(() -> paymentRepository.findRefundByReservationId(request.reservationId())
                                        .orElseThrow(() -> new IllegalStateException("Existing refund disappeared")));
                        paymentRepository.markPaymentRefunded(payment.id());
                        return toResponse(refund);
                    });
        }));
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
                payment.amount(),
                payment.currency(),
                payment.paymentMethodFingerprint(),
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

    private void validateSimulationDelay(Long delayMs) {
        if (delayMs != null && (delayMs < 0 || delayMs > MAX_SIMULATED_DELAY_MS)) {
            throw new IllegalArgumentException(
                    "Simulation delay must be between 0 and " + MAX_SIMULATED_DELAY_MS + " milliseconds");
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

    private record PaymentStart(PaymentRecord payment, boolean created) {
    }
}
