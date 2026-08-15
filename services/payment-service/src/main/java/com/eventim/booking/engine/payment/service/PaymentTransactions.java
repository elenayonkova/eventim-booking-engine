package com.eventim.booking.engine.payment.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentTransactions.class);

    private final PaymentRepository paymentRepository;
    private final Duration providerAttemptTimeout;

    public PaymentTransactions(
            PaymentRepository paymentRepository,
            @Value("${payment.provider-attempt-timeout}") Duration providerAttemptTimeout
    ) {
        Duration maximumSimulatedCall = Duration.ofMillis(
                PaymentService.MAX_SIMULATED_DELAY_MS);
        if (providerAttemptTimeout.compareTo(maximumSimulatedCall) <= 0) {
            throw new IllegalArgumentException(
                    "Provider attempt timeout must exceed the maximum simulated call duration");
        }
        this.paymentRepository = paymentRepository;
        this.providerAttemptTimeout = providerAttemptTimeout;
    }

    @Transactional
    public ProviderStep<PaymentResponse> startPayment(
            PaymentRequest request,
            String tokenDigest
    ) {
        PaymentRecord candidate = new PaymentRecord(
                UUID.randomUUID(),
                request.reservationId(),
                request.amount(),
                request.currency().toUpperCase(Locale.ROOT),
                request.paymentMethodToken(),
                tokenDigest,
                PaymentStatus.PROCESSING,
                1,
                null);
        LockedPayment locked = paymentRepository.lockOrCreatePayment(candidate);
        PaymentRecord current = locked.payment();
        if (!locked.created()) {
            ensureSameIdempotencyPayload(current, request, tokenDigest);
            if (current.status() == PaymentStatus.PROCESSING
                    && current.paymentMethodToken() == null) {
                current = paymentRepository.attachProcessingPaymentToken(
                        current.id(),
                        request.paymentMethodToken());
            }
        }

        PaymentResponse response = toResponse(current);
        if (current.status() != PaymentStatus.PROCESSING) {
            return ProviderStep.returnCurrent(response, current.attempt());
        }
        if (current.paymentMethodToken() == null) {
            LOGGER.warn(
                    "Processing payment {} predates automatic recovery and needs one matching client retry",
                    current.id());
            return ProviderStep.returnCurrent(response, current.attempt());
        }

        if (locked.created()) {
            return ProviderStep.callProvider(response, request, current.attempt());
        }

        Optional<PaymentRecord> claimed = paymentRepository.claimStaleProcessingPayment(
                current.id(),
                providerAttemptTimeout);
        if (claimed.isEmpty()) {
            return ProviderStep.returnCurrent(response, current.attempt());
        }

        PaymentRecord retry = claimed.get();
        LOGGER.warn(
                "Retrying stale provider payment {} with attempt {}",
                retry.id(),
                retry.attempt());
        return ProviderStep.callProvider(
                toResponse(retry),
                request,
                retry.attempt());
    }

    @Transactional(readOnly = true)
    public PaymentResponse findPayment(UUID reservationId) {
        PaymentRecord payment = paymentRepository.findPaymentByReservationId(reservationId)
                .orElseThrow(() -> new NotFoundException(
                        "Payment not found for reservation: " + reservationId));
        return toResponse(payment);
    }

    @Transactional
    public ProviderStep<PaymentResponse> startPaymentRecovery(UUID reservationId) {
        PaymentRecord current = paymentRepository.lockPayment(reservationId);
        PaymentResponse response = toResponse(current);
        if (current.status() != PaymentStatus.PROCESSING) {
            return ProviderStep.returnCurrent(response, current.attempt());
        }
        if (current.paymentMethodToken() == null) {
            LOGGER.warn(
                    "Processing payment {} predates automatic recovery and needs one matching client retry",
                    current.id());
            return ProviderStep.returnCurrent(response, current.attempt());
        }

        Optional<PaymentRecord> claimed = paymentRepository.claimStaleProcessingPayment(
                current.id(),
                providerAttemptTimeout);
        if (claimed.isEmpty()) {
            return ProviderStep.returnCurrent(response, current.attempt());
        }

        PaymentRecord retry = claimed.get();
        PaymentRequest providerRequest = new PaymentRequest(
                retry.reservationId(),
                retry.amount(),
                retry.currency(),
                retry.paymentMethodToken());
        LOGGER.warn(
                "Automatically retrying stale provider payment {} with attempt {}",
                retry.id(),
                retry.attempt());
        return ProviderStep.callProvider(
                toResponse(retry),
                providerRequest,
                retry.attempt());
    }

    @Transactional
    public PaymentResponse completePayment(
            PaymentRequest request,
            String tokenDigest,
            int attempt,
            OperationOutcome outcome
    ) {
        PaymentRecord current = paymentRepository.lockPayment(request.reservationId());
        ensureSameIdempotencyPayload(current, request, tokenDigest);
        if (current.status() != PaymentStatus.PROCESSING || current.attempt() != attempt) {
            return toResponse(current);
        }

        if (outcome == null) {
            throw new IllegalStateException("Payment provider returned no charge outcome");
        }
        PaymentStatus completedStatus = switch (outcome) {
            case SUCCEEDED -> PaymentStatus.SUCCEEDED;
            case FAILED -> PaymentStatus.FAILED;
        };
        PaymentRecord completed = paymentRepository.completeProcessingPayment(
                current.id(),
                attempt,
                completedStatus,
                completedStatus == PaymentStatus.FAILED
                        ? "Payment provider reported a failed charge"
                        : null);
        return toResponse(completed);
    }

    @Transactional
    public RefundStep startRefund(RefundRequest request) {
        UUID reservationId = request.reservationId();
        PaymentRecord payment = paymentRepository.findPaymentByReservationIdForUpdate(reservationId)
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

        if (outcome == null) {
            throw new IllegalStateException("Payment provider returned no refund outcome");
        }
        RefundStatus completedStatus = switch (outcome) {
            case SUCCEEDED -> RefundStatus.SUCCEEDED;
            case FAILED -> RefundStatus.FAILED;
        };
        RefundRecord completed = paymentRepository.completeProcessingRefund(
                current.id(),
                attempt,
                completedStatus);
        if (completed.status() == RefundStatus.SUCCEEDED) {
            paymentRepository.markPaymentRefunded(payment.id());
        }
        return toResponse(completed);
    }

    private void ensureSameIdempotencyPayload(
            PaymentRecord payment,
            PaymentRequest request,
            String tokenDigest
    ) {
        if (payment.amount() != request.amount()
                || !payment.currency().equalsIgnoreCase(request.currency())
                || !payment.paymentMethodTokenDigest().equals(tokenDigest)) {
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
        return new PaymentResponse(
                payment.id(),
                payment.reservationId(),
                payment.amount(),
                payment.currency(),
                payment.paymentMethodTokenDigest(),
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

record ProviderStep<T>(
        T response,
        boolean providerCallRequired,
        PaymentRequest providerRequest,
        int attempt
) {

    static <T> ProviderStep<T> callProvider(
            T response,
            PaymentRequest providerRequest,
            int attempt
    ) {
        return new ProviderStep<>(
                response,
                true,
                providerRequest,
                attempt);
    }

    static <T> ProviderStep<T> returnCurrent(T response, int attempt) {
        return new ProviderStep<>(response, false, null, attempt);
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
