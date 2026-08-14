package com.eventim.booking.engine.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.eventim.booking.engine.payment.provider.PaymentProvider.CancellationOutcome;
import com.eventim.booking.engine.payment.provider.PaymentProvider.OperationOutcome;
import com.eventim.booking.engine.payment.repository.PaymentRecord;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.repository.PaymentRepository.LockedPayment;
import com.eventim.booking.engine.payment.repository.RefundRecord;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionsTest {

    private static final long AMOUNT = 10_000;
    private static final String CURRENCY = "EUR";
    private static final String FINGERPRINT = "fingerprint";

    @Mock
    PaymentRepository paymentRepository;

    PaymentTransactions paymentTransactions;

    @BeforeEach
    void setUp() {
        paymentTransactions = new PaymentTransactions(paymentRepository);
    }

    @Test
    void idempotentProcessingPaymentDoesNotRefreshRecoveryTimeout() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord processing = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING);
        when(paymentRepository.lockOrCreatePayment(any()))
                .thenReturn(new LockedPayment(processing, false));

        ProviderStep<com.eventim.booking.engine.payment.api.PaymentResponse> step =
                paymentTransactions.startPayment(request(reservationId), FINGERPRINT);

        assertThat(step.providerCallRequired()).isFalse();
        assertThat(step.response().paymentId()).isEqualTo(processing.id());
        assertThat(step.response().status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).touchPayment(processing.id());
    }

    @Test
    void cancellationBeforePaymentBlocksLateCharge() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord tombstone = new PaymentRecord(
                UUID.randomUUID(),
                reservationId,
                null,
                null,
                null,
                PaymentStatus.CANCELLED,
                "Payment was cancelled before creation");
        when(paymentRepository.lockOrCreatePayment(any()))
                .thenReturn(new LockedPayment(tombstone, false));

        assertThatThrownBy(() -> paymentTransactions.startPayment(
                request(reservationId),
                FINGERPRINT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void latePaymentCompletionWhileCancellationIsPendingLeavesPaymentProcessing() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord cancelling = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.CANCELLATION_PENDING);
        when(paymentRepository.lockPayment(reservationId)).thenReturn(cancelling);

        var result = paymentTransactions.completePayment(
                request(reservationId),
                FINGERPRINT,
                OperationOutcome.SUCCEEDED);

        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).completeProcessingPayment(any(), any(), any());
    }

    @Test
    void pendingCancellationOutcomeTouchesTheAggregate() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord cancelling = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.CANCELLATION_PENDING);
        when(paymentRepository.lockPayment(reservationId)).thenReturn(cancelling);

        var result = paymentTransactions.completeCancellation(
                reservationId,
                CancellationOutcome.PENDING);

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository).touchPayment(cancelling.id());
        verify(paymentRepository, never()).completePendingCancellation(any(), any(), any());
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

    private PaymentRequest request(UUID reservationId) {
        return new PaymentRequest(reservationId, AMOUNT, CURRENCY, "pm-test");
    }

    private PaymentRecord payment(UUID paymentId, UUID reservationId, PaymentStatus status) {
        return new PaymentRecord(
                paymentId,
                reservationId,
                AMOUNT,
                CURRENCY,
                FINGERPRINT,
                status,
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
