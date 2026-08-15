package com.eventim.booking.engine.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
import com.eventim.booking.engine.payment.provider.PaymentProvider.OperationOutcome;
import com.eventim.booking.engine.payment.repository.PaymentRecord;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.repository.PaymentRepository.LockedPayment;
import com.eventim.booking.engine.payment.repository.RefundRecord;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionsTest {

    private static final long AMOUNT = 10_000;
    private static final String CURRENCY = "EUR";
    private static final String TOKEN_DIGEST = "token-digest";
    private static final Duration PROVIDER_ATTEMPT_TIMEOUT = Duration.ofSeconds(90);

    @Mock
    PaymentRepository paymentRepository;

    PaymentTransactions paymentTransactions;

    @BeforeEach
    void setUp() {
        paymentTransactions = new PaymentTransactions(
                paymentRepository,
                PROVIDER_ATTEMPT_TIMEOUT);
    }

    @Test
    void providerAttemptTimeoutMustExceedTheMaximumSimulatedCall() {
        assertThatThrownBy(() -> new PaymentTransactions(
                paymentRepository,
                Duration.ofMillis(PaymentService.MAX_SIMULATED_DELAY_MS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must exceed");
    }

    @Test
    void freshProcessingPaymentReturnsWithoutCallingTheProviderAgain() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord processing = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING);
        when(paymentRepository.lockOrCreatePayment(any()))
                .thenReturn(new LockedPayment(processing, false));
        when(paymentRepository.claimStaleProcessingPayment(
                processing.id(),
                PROVIDER_ATTEMPT_TIMEOUT)).thenReturn(Optional.empty());

        ProviderStep<com.eventim.booking.engine.payment.api.PaymentResponse> step =
                paymentTransactions.startPayment(request(reservationId), TOKEN_DIGEST);

        assertThat(step.providerCallRequired()).isFalse();
        assertThat(step.response().paymentId()).isEqualTo(processing.id());
        assertThat(step.response().status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(step.providerRequest()).isNull();
        assertThat(step.attempt()).isEqualTo(1);
    }

    @Test
    void staleProcessingPaymentWithDifferentPayloadIsRejected() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord processing = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING);
        when(paymentRepository.lockOrCreatePayment(any()))
                .thenReturn(new LockedPayment(processing, false));

        assertThatThrownBy(() -> paymentTransactions.startPayment(
                new PaymentRequest(reservationId, 1, "USD", "pm-other"),
                "other-token-digest"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different payment details");
        verify(paymentRepository, never()).claimStaleProcessingPayment(any(), any());
    }

    @Test
    void stalePaymentBeyondTheFormerAttemptCapStillRetries() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord processing = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING,
                5);
        PaymentRecord retry = payment(
                processing.id(),
                reservationId,
                PaymentStatus.PROCESSING,
                6);
        when(paymentRepository.lockOrCreatePayment(any()))
                .thenReturn(new LockedPayment(processing, false));
        when(paymentRepository.claimStaleProcessingPayment(
                processing.id(),
                PROVIDER_ATTEMPT_TIMEOUT)).thenReturn(Optional.of(retry));

        ProviderStep<com.eventim.booking.engine.payment.api.PaymentResponse> step =
                paymentTransactions.startPayment(request(reservationId), TOKEN_DIGEST);

        assertThat(step.providerCallRequired()).isTrue();
        assertThat(step.response().status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(step.attempt()).isEqualTo(6);
    }

    @Test
    void latePaymentCompletionCannotOverwriteANewerAttempt() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord retry = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING,
                2);
        when(paymentRepository.lockPayment(reservationId)).thenReturn(retry);

        var result = paymentTransactions.completePayment(
                request(reservationId),
                TOKEN_DIGEST,
                1,
                OperationOutcome.SUCCEEDED);

        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).completeProcessingPayment(
                any(), anyInt(), any(), any());
    }

    @Test
    void missingProviderChargeOutcomeDoesNotCompleteThePayment() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord processing = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING);
        when(paymentRepository.lockPayment(reservationId)).thenReturn(processing);

        assertThatThrownBy(() -> paymentTransactions.completePayment(
                request(reservationId), TOKEN_DIGEST, 1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no charge outcome");
        verify(paymentRepository, never()).completeProcessingPayment(
                any(), anyInt(), any(), any());
    }

    @Test
    void failedRefundRestartsWithTheSameIdAndCallsTheProvider() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord failed = refund(
                UUID.randomUUID(),
                succeeded,
                RefundStatus.FAILED,
                1);
        RefundRecord restarted = refund(
                failed.id(),
                succeeded,
                RefundStatus.PROCESSING,
                2);
        when(paymentRepository.findPaymentByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(succeeded));
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(failed));
        when(paymentRepository.restartFailedRefund(failed.id())).thenReturn(restarted);

        RefundStep step = paymentTransactions.startRefund(new RefundRequest(reservationId));

        assertThat(step.providerCallRequired()).isTrue();
        assertThat(step.response().refundId()).isEqualTo(failed.id());
        assertThat(step.response().status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(step.attempt()).isEqualTo(2);
    }

    @Test
    void processingRefundReturnsWithoutCallingTheProviderAgain() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord processing = refund(
                UUID.randomUUID(),
                succeeded,
                RefundStatus.PROCESSING,
                1);
        when(paymentRepository.findPaymentByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(succeeded));
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(processing));

        RefundStep step = paymentTransactions.startRefund(new RefundRequest(reservationId));

        assertThat(step.providerCallRequired()).isFalse();
        assertThat(step.response().status()).isEqualTo(RefundStatus.PROCESSING);
    }

    @Test
    void failedRefundBeyondTheFormerAttemptCapStillRetries() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord failed = refund(
                UUID.randomUUID(),
                succeeded,
                RefundStatus.FAILED,
                5);
        RefundRecord restarted = refund(
                failed.id(),
                succeeded,
                RefundStatus.PROCESSING,
                6);
        when(paymentRepository.findPaymentByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(succeeded));
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(failed));
        when(paymentRepository.restartFailedRefund(failed.id())).thenReturn(restarted);

        RefundStep step = paymentTransactions.startRefund(new RefundRequest(reservationId));

        assertThat(step.providerCallRequired()).isTrue();
        assertThat(step.response().status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(step.attempt()).isEqualTo(6);
    }

    @Test
    void lateCompletionFromAnOlderRefundAttemptCannotOverwriteTheRetry() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord retry = refund(
                UUID.randomUUID(),
                succeeded,
                RefundStatus.PROCESSING,
                2);
        when(paymentRepository.lockPayment(reservationId)).thenReturn(succeeded);
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(retry));

        var result = paymentTransactions.completeRefund(
                reservationId,
                retry.id(),
                1,
                OperationOutcome.SUCCEEDED);

        assertThat(result.status()).isEqualTo(RefundStatus.PROCESSING);
        verify(paymentRepository, never()).completeProcessingRefund(any(), anyInt(), any());
        verify(paymentRepository, never()).markPaymentRefunded(any());
    }

    @Test
    void successfulCurrentRefundAttemptMarksThePaymentRefunded() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord processing = refund(
                UUID.randomUUID(),
                succeeded,
                RefundStatus.PROCESSING,
                2);
        RefundRecord completed = refund(
                processing.id(),
                succeeded,
                RefundStatus.SUCCEEDED,
                2);
        when(paymentRepository.lockPayment(reservationId)).thenReturn(succeeded);
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(processing));
        when(paymentRepository.completeProcessingRefund(
                processing.id(),
                2,
                RefundStatus.SUCCEEDED)).thenReturn(completed);

        var result = paymentTransactions.completeRefund(
                reservationId,
                processing.id(),
                2,
                OperationOutcome.SUCCEEDED);

        assertThat(result.status()).isEqualTo(RefundStatus.SUCCEEDED);
        verify(paymentRepository).markPaymentRefunded(succeeded.id());
    }

    @Test
    void missingProviderRefundOutcomeDoesNotCompleteTheRefund() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord processing = refund(
                UUID.randomUUID(),
                succeeded,
                RefundStatus.PROCESSING,
                1);
        when(paymentRepository.lockPayment(reservationId)).thenReturn(succeeded);
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(processing));

        assertThatThrownBy(() -> paymentTransactions.completeRefund(
                reservationId, processing.id(), 1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no refund outcome");
        verify(paymentRepository, never()).completeProcessingRefund(any(), anyInt(), any());
    }

    private PaymentRequest request(UUID reservationId) {
        return new PaymentRequest(reservationId, AMOUNT, CURRENCY, "pm-test");
    }

    private PaymentRecord payment(UUID paymentId, UUID reservationId, PaymentStatus status) {
        return payment(paymentId, reservationId, status, 1);
    }

    private PaymentRecord payment(
            UUID paymentId,
            UUID reservationId,
            PaymentStatus status,
            int attempt
    ) {
        return new PaymentRecord(
                paymentId,
                reservationId,
                AMOUNT,
                CURRENCY,
                status == PaymentStatus.PROCESSING ? "pm-test" : null,
                TOKEN_DIGEST,
                status,
                attempt,
                null);
    }

    private RefundRecord refund(
            UUID refundId,
            PaymentRecord payment,
            RefundStatus status,
            int attempt
    ) {
        return new RefundRecord(
                refundId,
                payment.reservationId(),
                payment.id(),
                status,
                attempt);
    }
}
