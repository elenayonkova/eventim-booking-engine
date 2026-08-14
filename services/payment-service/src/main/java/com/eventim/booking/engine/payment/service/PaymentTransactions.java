package com.eventim.booking.engine.payment.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.payment.api.PaymentCancellationResponse;
import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.domain.PaymentIntentStatus;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
import com.eventim.booking.engine.payment.provider.PaymentProvider.CancellationOutcome;
import com.eventim.booking.engine.payment.provider.PaymentProvider.OperationOutcome;
import com.eventim.booking.engine.payment.repository.PaymentRecord;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.repository.RefundRecord;

/**
 * Owns every local, transactional state transition in the payment workflow.
 * Provider calls are deliberately orchestrated by {@link PaymentService} so
 * that no database transaction is held while waiting.
 */
@Component
public class PaymentTransactions {

    private final PaymentRepository paymentRepository;

    public PaymentTransactions(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public PaymentResponse getPayment(UUID reservationId) {
        Optional<PaymentRecord> payment = paymentRepository.findPaymentByReservationId(reservationId);
        if (payment.isEmpty()) {
            throw new NotFoundException("Payment not found for reservation: " + reservationId);
        }

        return toResponse(payment.get());
    }

    @Transactional
    public ProviderStep<PaymentResponse> startPayment(
            PaymentRequest request,
            UUID idempotencyKey,
            String fingerprint
    ) {
        PaymentIntentStatus intent = paymentRepository.lockOrCreatePaymentIntent(
                idempotencyKey,
                PaymentIntentStatus.ACTIVE);
        if (intent == PaymentIntentStatus.CANCELLED) {
            throw new ConflictException(
                    "Payment was cancelled for reservation: " + idempotencyKey);
        }
        if (intent == PaymentIntentStatus.CANCELLATION_PENDING) {
            PaymentRecord existingPayment = paymentRepository.findPaymentByReservationId(
                            idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cancellation-pending payment disappeared"));
            ensureSameIdempotencyPayload(existingPayment, request, fingerprint);
            return ProviderStep.returnCurrent(toResponse(existingPayment));
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
            return ProviderStep.callProvider(toResponse(inserted.get()));
        }

        Optional<PaymentRecord> existingPayment = paymentRepository.findPaymentByReservationId(
                idempotencyKey);
        if (existingPayment.isEmpty()) {
            throw new IllegalStateException("Existing payment disappeared");
        }

        ensureSameIdempotencyPayload(existingPayment.get(), request, fingerprint);
        return ProviderStep.returnCurrent(toResponse(existingPayment.get()));
    }

    @Transactional
    public PaymentResponse completePayment(
            PaymentRequest request,
            UUID idempotencyKey,
            String fingerprint,
            OperationOutcome outcome
    ) {
        PaymentIntentStatus intent = paymentRepository.lockPaymentIntent(idempotencyKey);
        Optional<PaymentRecord> foundPayment = paymentRepository.findPaymentByReservationId(idempotencyKey);
        if (foundPayment.isEmpty()) {
            throw new IllegalStateException("Processing payment disappeared");
        }

        PaymentRecord current = foundPayment.get();
        ensureSameIdempotencyPayload(current, request, fingerprint);
        if (intent != PaymentIntentStatus.ACTIVE
                || current.status() != PaymentStatus.PROCESSING) {
            return toResponse(current);
        }

        boolean failed = outcome == OperationOutcome.FAILED;
        PaymentStatus finalStatus = failed ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
        String failureReason = failed ? "Payment provider reported a failed charge" : null;
        PaymentRecord completed = paymentRepository.completeProcessingPayment(
                current.id(),
                finalStatus,
                failureReason);

        return toResponse(completed);
    }

    @Transactional
    public ProviderStep<PaymentCancellationResponse> startCancellation(UUID reservationId) {
        PaymentIntentStatus intent = paymentRepository.lockOrCreatePaymentIntent(
                reservationId,
                PaymentIntentStatus.CANCELLED);
        Optional<PaymentRecord> foundPayment = paymentRepository.findPaymentByReservationIdForUpdate(
                reservationId);

        if (foundPayment.isEmpty()) {
            if (intent != PaymentIntentStatus.CANCELLED) {
                paymentRepository.updatePaymentIntentStatus(
                        reservationId,
                        PaymentIntentStatus.CANCELLED);
            }
            return ProviderStep.returnCurrent(
                    new PaymentCancellationResponse(reservationId, null));
        }

        PaymentRecord payment = foundPayment.get();
        if (payment.status() == PaymentStatus.PROCESSING) {
            if (intent == PaymentIntentStatus.CANCELLED) {
                PaymentRecord cancelled = paymentRepository.completeProcessingPayment(
                        payment.id(),
                        PaymentStatus.FAILED,
                        "Payment cancellation was already confirmed");
                return ProviderStep.returnCurrent(new PaymentCancellationResponse(
                        reservationId,
                        toResponse(cancelled)));
            }
            if (intent != PaymentIntentStatus.CANCELLATION_PENDING) {
                paymentRepository.updatePaymentIntentStatus(
                        reservationId,
                        PaymentIntentStatus.CANCELLATION_PENDING);
            }
            return ProviderStep.callProvider(new PaymentCancellationResponse(
                    reservationId,
                    toResponse(payment)));
        }

        reconcileTerminalPaymentIntent(reservationId, payment.status());
        return ProviderStep.returnCurrent(new PaymentCancellationResponse(
                reservationId,
                toResponse(payment)));
    }

    @Transactional
    public PaymentCancellationResponse completeCancellation(
            UUID reservationId,
            UUID paymentId,
            CancellationOutcome outcome
    ) {
        PaymentIntentStatus intent = paymentRepository.lockPaymentIntent(reservationId);
        PaymentRecord payment = requireExistingPaymentForUpdate(
                reservationId,
                "Cancellation-pending payment disappeared");
        if (!payment.id().equals(paymentId)) {
            throw new IllegalStateException("Another payment replaced the cancellation-pending payment");
        }

        if (payment.status() != PaymentStatus.PROCESSING) {
            reconcileTerminalPaymentIntent(reservationId, payment.status());
            return new PaymentCancellationResponse(reservationId, toResponse(payment));
        }
        if (intent != PaymentIntentStatus.CANCELLATION_PENDING
                || outcome == CancellationOutcome.PENDING) {
            return new PaymentCancellationResponse(reservationId, toResponse(payment));
        }

        PaymentRecord completed;
        if (outcome == CancellationOutcome.CANCELLED) {
            completed = paymentRepository.completeProcessingPayment(
                    payment.id(),
                    PaymentStatus.FAILED,
                    "Payment provider confirmed cancellation");
        } else {
            completed = paymentRepository.completeProcessingPayment(
                    payment.id(),
                    PaymentStatus.SUCCEEDED,
                    null);
        }

        reconcileTerminalPaymentIntent(reservationId, completed.status());
        return new PaymentCancellationResponse(reservationId, toResponse(completed));
    }

    @Transactional
    public ProviderStep<RefundResponse> startRefund(RefundRequest request) {
        UUID idempotencyKey = request.reservationId();
        PaymentRecord payment = requirePaymentForUpdate(idempotencyKey);
        if (payment.status() == PaymentStatus.FAILED || payment.status() == PaymentStatus.PROCESSING) {
            throw new ConflictException("Only a successful payment can be refunded");
        }

        Optional<RefundRecord> existingRefund = paymentRepository.findRefundByReservationId(
                idempotencyKey);
        if (existingRefund.isPresent()) {
            ensureRefundMatchesPayment(existingRefund.get(), payment);
            return ProviderStep.returnCurrent(toResponse(existingRefund.get()));
        }

        Optional<RefundRecord> insertedRefund = paymentRepository.insertRefundIfAbsent(
                UUID.randomUUID(),
                idempotencyKey,
                payment.id(),
                RefundStatus.PROCESSING);

        if (insertedRefund.isPresent()) {
            return ProviderStep.callProvider(toResponse(insertedRefund.get()));
        }

        Optional<RefundRecord> foundRefund = paymentRepository.findRefundByReservationId(
                idempotencyKey);
        if (foundRefund.isEmpty()) {
            throw new IllegalStateException("Existing refund disappeared");
        }

        ensureRefundMatchesPayment(foundRefund.get(), payment);
        return ProviderStep.returnCurrent(toResponse(foundRefund.get()));
    }

    @Transactional
    public RefundResponse completeRefund(
            UUID reservationId,
            UUID refundId,
            OperationOutcome outcome
    ) {
        PaymentRecord payment = requireExistingPaymentForUpdate(
                reservationId,
                "Refunded payment disappeared");
        RefundRecord current = paymentRepository.findRefundByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalStateException("Processing refund disappeared"));
        ensureRefundMatchesPayment(current, payment);
        if (!current.id().equals(refundId)) {
            throw new IllegalStateException("Another refund replaced the processing refund");
        }
        if (current.status() != RefundStatus.PROCESSING) {
            return toResponse(current);
        }

        RefundStatus finalStatus = outcome == OperationOutcome.FAILED
                ? RefundStatus.FAILED
                : RefundStatus.SUCCEEDED;
        RefundRecord completed = paymentRepository.completeProcessingRefund(
                current.id(),
                finalStatus);
        if (completed.status() == RefundStatus.SUCCEEDED) {
            paymentRepository.markPaymentRefunded(payment.id());
        }

        return toResponse(completed);
    }

    private void ensureSameIdempotencyPayload(
            PaymentRecord existingPayment,
            PaymentRequest request,
            String fingerprint
    ) {
        if (existingPayment.amount() != request.amount()
                || !existingPayment.currency().equalsIgnoreCase(request.currency())
                || !existingPayment.paymentMethodFingerprint().equals(fingerprint)) {
            throw new ConflictException(
                    "Payment already exists for reservation with different payment details");
        }
    }

    private void ensureRefundMatchesPayment(RefundRecord refund, PaymentRecord payment) {
        if (!refund.reservationId().equals(payment.reservationId())
                || !refund.paymentId().equals(payment.id())) {
            throw new IllegalStateException("Refund does not match its payment");
        }
    }

    private PaymentRecord requirePaymentForUpdate(UUID reservationId) {
        return paymentRepository.findPaymentByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new NotFoundException(
                        "Payment not found for reservation: " + reservationId));
    }

    private PaymentRecord requireExistingPaymentForUpdate(
            UUID reservationId,
            String missingMessage
    ) {
        return paymentRepository.findPaymentByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalStateException(missingMessage));
    }

    private void reconcileTerminalPaymentIntent(
            UUID reservationId,
            PaymentStatus paymentStatus
    ) {
        PaymentIntentStatus intentStatus = paymentStatus == PaymentStatus.FAILED
                ? PaymentIntentStatus.CANCELLED
                : PaymentIntentStatus.ACTIVE;
        paymentRepository.updatePaymentIntentStatus(reservationId, intentStatus);
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
}

/**
 * Result of preparing a provider-backed operation. It carries the durable
 * current response and tells the orchestrator whether external work is needed.
 */
record ProviderStep<T>(T response, boolean providerCallRequired) {

    static <T> ProviderStep<T> callProvider(T response) {
        return new ProviderStep<>(response, true);
    }

    static <T> ProviderStep<T> returnCurrent(T response) {
        return new ProviderStep<>(response, false);
    }
}
