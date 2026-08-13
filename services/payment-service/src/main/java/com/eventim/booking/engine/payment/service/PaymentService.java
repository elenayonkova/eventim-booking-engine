package com.eventim.booking.engine.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.PaymentCancellationRequest;
import com.eventim.booking.engine.payment.api.PaymentCancellationResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.PaymentIntentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
import com.eventim.booking.engine.payment.repository.PaymentRecord;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.repository.RefundRecord;

@Service
public class PaymentService {

    public static final long MAX_SIMULATED_DELAY_MS = 60_000;

    private final PaymentRepository paymentRepository;
    private final PlatformTransactionManager transactionManager;
    private final boolean simulationEnabled;

    public PaymentService(
            PaymentRepository paymentRepository,
            PlatformTransactionManager transactionManager,
            @Value("${payment.simulation-enabled:true}") boolean simulationEnabled
    ) {
        this.paymentRepository = paymentRepository;
        this.transactionManager = transactionManager;
        this.simulationEnabled = simulationEnabled;
    }

    public PaymentResponse createPayment(PaymentRequest request, Long delayMs, String simulateFailure) {
        validateSimulation(delayMs, simulateFailure);
        String fingerprint = fingerprint(request.paymentMethodToken());
        UUID idempotencyKey = idempotencyKeyFor(request);
        PaymentStart start = startPaymentInTransaction(request, idempotencyKey, fingerprint);

        if (!start.created()) {
            return toResponse(start.payment());
        }

        // Provider latency happens after the durable PROCESSING record is
        // committed, without holding a transaction or database connection.
        sleepIfRequested(delayMs);
        return completePaymentInTransaction(request, idempotencyKey, fingerprint, simulateFailure);
    }

    public PaymentResponse getPayment(UUID reservationId) {
        UUID idempotencyKey = reservationId;
        Optional<PaymentRecord> payment = paymentRepository.findPaymentByReservationId(idempotencyKey);
        if (payment.isEmpty()) {
            throw new NotFoundException("Payment not found for reservation: " + idempotencyKey);
        }

        return toResponse(payment.get());
    }

    public PaymentCancellationResponse cancelPayment(PaymentCancellationRequest request) {
        return cancelPaymentInTransaction(request.reservationId());
    }

    public RefundResponse refund(RefundRequest request) {
        return refundInTransaction(request);
    }

    private PaymentStart startPaymentInTransaction(
            PaymentRequest request,
            UUID idempotencyKey,
            String fingerprint
    ) {
        try (PaymentTransaction transaction = startPaymentTransaction()) {
            PaymentIntentStatus intent = paymentRepository.lockOrCreatePaymentIntent(
                    idempotencyKey,
                    PaymentIntentStatus.ACTIVE);
            if (intent == PaymentIntentStatus.CANCELLED) {
                throw new ConflictException(
                        "Payment was cancelled for reservation: " + idempotencyKey);
            }

            Optional<PaymentRecord> inserted = paymentRepository.insertPaymentIfAbsent(
                    UUID.randomUUID(),
                    idempotencyKey,
                    request.amount(),
                    request.currency().toUpperCase(),
                    fingerprint,
                    PaymentStatus.PROCESSING,
                    null);
            if (inserted.isPresent()) {
                PaymentStart start = new PaymentStart(inserted.get(), true);
                transaction.commit();
                return start;
            }

            Optional<PaymentRecord> existingPayment = paymentRepository.findPaymentByReservationId(
                    idempotencyKey);
            if (existingPayment.isEmpty()) {
                throw new IllegalStateException("Existing payment disappeared");
            }

            ensureSameIdempotencyPayload(existingPayment.get(), request, fingerprint);
            PaymentStart start = new PaymentStart(existingPayment.get(), false);
            transaction.commit();
            return start;
        }
    }

    private PaymentResponse completePaymentInTransaction(
            PaymentRequest request,
            UUID idempotencyKey,
            String fingerprint,
            String simulateFailure
    ) {
        try (PaymentTransaction transaction = startPaymentTransaction()) {
            PaymentIntentStatus intent = paymentRepository.lockPaymentIntent(idempotencyKey);
            Optional<PaymentRecord> foundPayment = paymentRepository.findPaymentByReservationId(idempotencyKey);
            if (foundPayment.isEmpty()) {
                throw new IllegalStateException("Processing payment disappeared");
            }

            PaymentRecord current = foundPayment.get();
            ensureSameIdempotencyPayload(current, request, fingerprint);
            if (intent == PaymentIntentStatus.CANCELLED) {
                PaymentResponse response = toResponse(current);
                transaction.commit();
                return response;
            }
            if (current.status() != PaymentStatus.PROCESSING) {
                PaymentResponse response = toResponse(current);
                transaction.commit();
                return response;
            }

            boolean failed = isSimulatedFailure(simulateFailure);
            PaymentStatus finalStatus = failed ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
            String failureReason = failed ? "Simulated payment failure" : null;
            PaymentRecord completed = paymentRepository.completeProcessingPayment(
                    current.id(),
                    finalStatus,
                    failureReason);

            PaymentResponse response = toResponse(completed);
            transaction.commit();
            return response;
        }
    }

    private PaymentCancellationResponse cancelPaymentInTransaction(UUID reservationId) {
        try (PaymentTransaction transaction = startPaymentTransaction()) {
            PaymentIntentStatus intent = paymentRepository.lockOrCreatePaymentIntent(
                    reservationId,
                    PaymentIntentStatus.CANCELLED);
            Optional<PaymentRecord> foundPayment = paymentRepository.findPaymentByReservationIdForUpdate(
                    reservationId);

            if (foundPayment.isEmpty()) {
                if (intent != PaymentIntentStatus.CANCELLED) {
                    paymentRepository.markPaymentIntentCancelled(reservationId);
                }
                PaymentCancellationResponse response = new PaymentCancellationResponse(
                        reservationId,
                        null);
                transaction.commit();
                return response;
            }

            PaymentRecord payment = foundPayment.get();
            if (payment.status() == PaymentStatus.PROCESSING) {
                payment = paymentRepository.completeProcessingPayment(
                        payment.id(),
                        PaymentStatus.FAILED,
                        "Payment was cancelled before completion");
                paymentRepository.markPaymentIntentCancelled(reservationId);
            } else if (payment.status() == PaymentStatus.FAILED) {
                paymentRepository.markPaymentIntentCancelled(reservationId);
            }

            PaymentCancellationResponse response = new PaymentCancellationResponse(
                    reservationId,
                    toResponse(payment));
            transaction.commit();
            return response;
        }
    }

    private RefundResponse refundInTransaction(RefundRequest request) {
        UUID idempotencyKey = request.reservationId();
        try (PaymentTransaction transaction = startPaymentTransaction()) {
            Optional<PaymentRecord> foundPayment = paymentRepository.findPaymentByReservationIdForUpdate(
                    idempotencyKey);
            if (foundPayment.isEmpty()) {
                throw new NotFoundException("Payment not found for reservation: " + idempotencyKey);
            }

            PaymentRecord payment = foundPayment.get();
            if (payment.status() == PaymentStatus.FAILED || payment.status() == PaymentStatus.PROCESSING) {
                throw new ConflictException("Only a successful payment can be refunded");
            }

            Optional<RefundRecord> existingRefund = paymentRepository.findRefundByReservationId(
                    idempotencyKey);
            if (existingRefund.isPresent()) {
                RefundResponse response = toResponse(existingRefund.get());
                transaction.commit();
                return response;
            }

            Optional<RefundRecord> insertedRefund = paymentRepository.insertRefundIfAbsent(
                    UUID.randomUUID(),
                    idempotencyKey,
                    payment.id(),
                    RefundStatus.SUCCEEDED);

            RefundRecord refund;
            if (insertedRefund.isPresent()) {
                refund = insertedRefund.get();
            } else {
                Optional<RefundRecord> foundRefund = paymentRepository.findRefundByReservationId(
                        idempotencyKey);
                if (foundRefund.isEmpty()) {
                    throw new IllegalStateException("Existing refund disappeared");
                }
                refund = foundRefund.get();
            }

            paymentRepository.markPaymentRefunded(payment.id());
            RefundResponse response = toResponse(refund);
            transaction.commit();
            return response;
        }
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

    private void validateSimulation(Long delayMs, String simulateFailure) {
        if (!simulationEnabled && simulationRequested(delayMs, simulateFailure)) {
            throw new IllegalArgumentException("Payment simulation is disabled");
        }
        validateSimulationDelay(delayMs);
    }

    private boolean simulationRequested(Long delayMs, String simulateFailure) {
        if (delayMs != null) {
            return true;
        }
        return simulateFailure != null && !simulateFailure.isBlank();
    }

    private UUID idempotencyKeyFor(PaymentRequest request) {
        return request.reservationId();
    }

    private PaymentTransaction startPaymentTransaction() {
        return new PaymentTransaction();
    }

    private void rollbackIfNeeded(TransactionStatus transaction) {
        if (!transaction.isCompleted()) {
            transactionManager.rollback(transaction);
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

    private final class PaymentTransaction implements AutoCloseable {

        private final TransactionStatus transaction;
        private boolean committed;

        private PaymentTransaction() {
            transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
        }

        private void commit() {
            transactionManager.commit(transaction);
            committed = true;
        }

        @Override
        public void close() {
            if (!committed) {
                rollbackIfNeeded(transaction);
            }
        }
    }
}
