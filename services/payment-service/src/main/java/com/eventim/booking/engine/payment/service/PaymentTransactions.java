package com.eventim.booking.engine.payment.service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.payment.api.PaymentCancellationResponse;
import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
import com.eventim.booking.engine.payment.provider.PaymentProvider.CancellationOutcome;
import com.eventim.booking.engine.payment.provider.PaymentProvider.OperationOutcome;
import com.eventim.booking.engine.payment.repository.PaymentRecord;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.repository.PaymentRepository.LockedPayment;
import com.eventim.booking.engine.payment.repository.RefundRecord;

/**
 * Owns every local, transactional payment transition. Provider calls are
 * orchestrated by {@link PaymentService} outside these transactions.
 */
@Component
public class PaymentTransactions {

    private final PaymentRepository paymentRepository;

    public PaymentTransactions(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public PaymentResponse getPayment(UUID reservationId) {
        PaymentRecord payment = paymentRepository.findPaymentByReservationId(reservationId)
                .filter(PaymentRecord::hasPayload)
                .orElseThrow(() -> new NotFoundException(
                        "Payment not found for reservation: " + reservationId));
        return toResponse(payment);
    }

    @Transactional
    public ProviderStep<PaymentResponse> startPayment(
            PaymentRequest request,
            String fingerprint
    ) {
        PaymentRecord candidate = new PaymentRecord(
                UUID.randomUUID(),
                request.reservationId(),
                request.amount(),
                request.currency().toUpperCase(Locale.ROOT),
                fingerprint,
                PaymentStatus.PROCESSING,
                null);
        LockedPayment locked = paymentRepository.lockOrCreatePayment(candidate);
        PaymentRecord current = locked.payment();

        if (!current.hasPayload() || current.status() == PaymentStatus.CANCELLED) {
            throw new ConflictException(
                    "Payment was cancelled for reservation: " + request.reservationId());
        }
        ensureSameIdempotencyPayload(current, request, fingerprint);

        PaymentResponse response = toResponse(current);
        return locked.created()
                ? ProviderStep.callProvider(response)
                : ProviderStep.returnCurrent(response);
    }

    @Transactional
    public PaymentResponse completePayment(
            PaymentRequest request,
            String fingerprint,
            OperationOutcome outcome
    ) {
        PaymentRecord current = paymentRepository.lockPayment(request.reservationId());
        ensureSameIdempotencyPayload(current, request, fingerprint);
        if (current.status() != PaymentStatus.PROCESSING) {
            return toResponse(current);
        }

        boolean failed = outcome == OperationOutcome.FAILED;
        PaymentRecord completed = paymentRepository.completeProcessingPayment(
                current.id(),
                failed ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED,
                failed ? "Payment provider reported a failed charge" : null);
        return toResponse(completed);
    }

    @Transactional
    public ProviderStep<PaymentCancellationResponse> startCancellation(UUID reservationId) {
        PaymentRecord tombstone = new PaymentRecord(
                UUID.randomUUID(),
                reservationId,
                null,
                null,
                null,
                PaymentStatus.CANCELLED,
                "Payment was cancelled before creation");
        PaymentRecord payment = paymentRepository.lockOrCreatePayment(tombstone).payment();

        if (!payment.hasPayload()) {
            return ProviderStep.returnCurrent(
                    new PaymentCancellationResponse(reservationId, null));
        }
        if (payment.status() == PaymentStatus.PROCESSING) {
            payment = paymentRepository.markCancellationPending(payment.id());
        } else if (payment.status() == PaymentStatus.CANCELLATION_PENDING) {
            paymentRepository.touchPayment(payment.id());
        }
        if (payment.status() == PaymentStatus.CANCELLATION_PENDING) {
            return ProviderStep.callProvider(new PaymentCancellationResponse(
                    reservationId,
                    toResponse(payment)));
        }

        return ProviderStep.returnCurrent(new PaymentCancellationResponse(
                reservationId,
                toResponse(payment)));
    }

    @Transactional
    public PaymentCancellationResponse completeCancellation(
            UUID reservationId,
            CancellationOutcome outcome
    ) {
        PaymentRecord payment = paymentRepository.lockPayment(reservationId);
        if (payment.status() != PaymentStatus.CANCELLATION_PENDING) {
            return new PaymentCancellationResponse(reservationId, toResponse(payment));
        }
        if (outcome == CancellationOutcome.PENDING) {
            paymentRepository.touchPayment(payment.id());
            return new PaymentCancellationResponse(reservationId, toResponse(payment));
        }

        PaymentRecord completed = paymentRepository.completePendingCancellation(
                payment.id(),
                outcome == CancellationOutcome.CANCELLED
                        ? PaymentStatus.CANCELLED
                        : PaymentStatus.SUCCEEDED,
                outcome == CancellationOutcome.CANCELLED
                        ? "Payment provider confirmed cancellation"
                        : null);
        return new PaymentCancellationResponse(reservationId, toResponse(completed));
    }

    @Transactional
    public RefundStep startRefund(RefundRequest request) {
        UUID reservationId = request.reservationId();
        PaymentRecord payment = paymentRepository.findPaymentByReservationIdForUpdate(reservationId)
                .filter(PaymentRecord::hasPayload)
                .orElseThrow(() -> new NotFoundException(
                        "Payment not found for reservation: " + reservationId));
        if (payment.status() != PaymentStatus.SUCCEEDED
                && payment.status() != PaymentStatus.REFUNDED) {
            throw new ConflictException("Only a successful payment can be refunded");
        }

        Optional<RefundRecord> existing = paymentRepository.findRefundByReservationIdForUpdate(
                reservationId);
        if (existing.isPresent()) {
            RefundRecord refund = existing.get();
            ensureRefundMatchesPayment(refund, payment);
            if (refund.status() == RefundStatus.FAILED) {
                RefundRecord restarted = paymentRepository.restartFailedRefund(refund.id());
                return RefundStep.callProvider(toResponse(restarted), restarted.attempt());
            }
            return RefundStep.returnCurrent(toResponse(refund), refund.attempt());
        }
        if (payment.status() == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Refunded payment has no refund record");
        }

        RefundRecord created = paymentRepository.insertRefund(
                UUID.randomUUID(),
                reservationId,
                payment.id());
        return RefundStep.callProvider(toResponse(created), created.attempt());
    }

    @Transactional
    public RefundResponse completeRefund(
            UUID reservationId,
            UUID refundId,
            int attempt,
            OperationOutcome outcome
    ) {
        PaymentRecord payment = paymentRepository.lockPayment(reservationId);
        RefundRecord current = paymentRepository.findRefundByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalStateException("Processing refund disappeared"));
        ensureRefundMatchesPayment(current, payment);

        if (!current.id().equals(refundId)
                || current.attempt() != attempt
                || current.status() != RefundStatus.PROCESSING) {
            return toResponse(current);
        }

        RefundRecord completed = paymentRepository.completeProcessingRefund(
                current.id(),
                attempt,
                outcome == OperationOutcome.FAILED
                        ? RefundStatus.FAILED
                        : RefundStatus.SUCCEEDED);
        if (completed.status() == RefundStatus.SUCCEEDED) {
            paymentRepository.markPaymentRefunded(payment.id());
        }
        return toResponse(completed);
    }

    private void ensureSameIdempotencyPayload(
            PaymentRecord payment,
            PaymentRequest request,
            String fingerprint
    ) {
        if (!payment.hasPayload()
                || payment.amount() != request.amount()
                || !payment.currency().equalsIgnoreCase(request.currency())
                || !payment.paymentMethodFingerprint().equals(fingerprint)) {
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

    private PaymentResponse toResponse(PaymentRecord payment) {
        PaymentStatus responseStatus = switch (payment.status()) {
            case CANCELLATION_PENDING, UNKNOWN -> PaymentStatus.PROCESSING;
            case CANCELLED -> PaymentStatus.FAILED;
            default -> payment.status();
        };
        return new PaymentResponse(
                payment.id(),
                payment.reservationId(),
                payment.amount(),
                payment.currency(),
                payment.paymentMethodFingerprint(),
                responseStatus,
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

record ProviderStep<T>(T response, boolean providerCallRequired) {

    static <T> ProviderStep<T> callProvider(T response) {
        return new ProviderStep<>(response, true);
    }

    static <T> ProviderStep<T> returnCurrent(T response) {
        return new ProviderStep<>(response, false);
    }
}

record RefundStep(RefundResponse response, boolean providerCallRequired, int attempt) {

    static RefundStep callProvider(RefundResponse response, int attempt) {
        return new RefundStep(response, true, attempt);
    }

    static RefundStep returnCurrent(RefundResponse response, int attempt) {
        return new RefundStep(response, false, attempt);
    }
}
