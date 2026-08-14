package com.eventim.booking.engine.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.eventim.booking.engine.payment.domain.PaymentIntentStatus;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
import com.eventim.booking.engine.payment.provider.PaymentProvider.CancellationOutcome;
import com.eventim.booking.engine.payment.provider.PaymentProvider.OperationOutcome;
import com.eventim.booking.engine.payment.repository.PaymentRecord;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
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
    void paymentDuringCancellationPendingReturnsStoredPaymentWithoutCallingProvider() {
        UUID reservationId = UUID.randomUUID();
        PaymentRequest request = request(reservationId);
        PaymentRecord processing = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING);
        when(paymentRepository.lockOrCreatePaymentIntent(
                reservationId,
                PaymentIntentStatus.ACTIVE))
                .thenReturn(PaymentIntentStatus.CANCELLATION_PENDING);
        when(paymentRepository.findPaymentByReservationId(reservationId))
                .thenReturn(Optional.of(processing));

        ProviderStep<com.eventim.booking.engine.payment.api.PaymentResponse> step =
                paymentTransactions.startPayment(request, reservationId, FINGERPRINT);

        assertThat(step.providerCallRequired()).isFalse();
        assertThat(step.response().paymentId()).isEqualTo(processing.id());
        assertThat(step.response().status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).insertPaymentIfAbsent(
                any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void latePaymentCompletionWhileCancellationIsPendingLeavesPaymentProcessing() {
        UUID reservationId = UUID.randomUUID();
        PaymentRequest request = request(reservationId);
        PaymentRecord processing = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.PROCESSING);
        when(paymentRepository.lockPaymentIntent(reservationId))
                .thenReturn(PaymentIntentStatus.CANCELLATION_PENDING);
        when(paymentRepository.findPaymentByReservationId(reservationId))
                .thenReturn(Optional.of(processing));

        var result = paymentTransactions.completePayment(
                request,
                reservationId,
                FINGERPRINT,
                OperationOutcome.SUCCEEDED);

        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).completeProcessingPayment(
                any(), any(), any());
    }

    @Test
    void pendingCancellationOutcomeLeavesPaymentAndIntentUnchanged() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentRecord processing = payment(
                paymentId,
                reservationId,
                PaymentStatus.PROCESSING);
        when(paymentRepository.lockPaymentIntent(reservationId))
                .thenReturn(PaymentIntentStatus.CANCELLATION_PENDING);
        when(paymentRepository.findPaymentByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(processing));

        var result = paymentTransactions.completeCancellation(
                reservationId,
                paymentId,
                CancellationOutcome.PENDING);

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).completeProcessingPayment(any(), any(), any());
        verify(paymentRepository, never()).updatePaymentIntentStatus(any(), any());
    }

    @Test
    void existingRefundIsReturnedWithoutCallingProviderAgain() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord existing = new RefundRecord(
                UUID.randomUUID(),
                reservationId,
                succeeded.id(),
                RefundStatus.FAILED);
        when(paymentRepository.findPaymentByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(succeeded));
        when(paymentRepository.findRefundByReservationId(reservationId))
                .thenReturn(Optional.of(existing));

        ProviderStep<com.eventim.booking.engine.payment.api.RefundResponse> step =
                paymentTransactions.startRefund(new RefundRequest(reservationId));

        assertThat(step.providerCallRequired()).isFalse();
        assertThat(step.response().refundId()).isEqualTo(existing.id());
        assertThat(step.response().status()).isEqualTo(RefundStatus.FAILED);
        verify(paymentRepository, never()).insertRefundIfAbsent(any(), any(), any(), any());
    }

    @Test
    void terminalRefundCompletionDoesNotMarkPaymentRefunded() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord failed = new RefundRecord(
                UUID.randomUUID(),
                reservationId,
                succeeded.id(),
                RefundStatus.FAILED);
        when(paymentRepository.findPaymentByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(succeeded));
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(failed));

        var result = paymentTransactions.completeRefund(
                reservationId,
                failed.id(),
                OperationOutcome.SUCCEEDED);

        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
        verify(paymentRepository, never()).completeProcessingRefund(any(), any());
        verify(paymentRepository, never()).markPaymentRefunded(any());
    }

    @Test
    void completionForReplacedRefundIsRejected() {
        UUID reservationId = UUID.randomUUID();
        PaymentRecord succeeded = payment(
                UUID.randomUUID(),
                reservationId,
                PaymentStatus.SUCCEEDED);
        RefundRecord current = new RefundRecord(
                UUID.randomUUID(),
                reservationId,
                succeeded.id(),
                RefundStatus.PROCESSING);
        when(paymentRepository.findPaymentByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(succeeded));
        when(paymentRepository.findRefundByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> paymentTransactions.completeRefund(
                reservationId,
                UUID.randomUUID(),
                OperationOutcome.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Another refund replaced");

        verify(paymentRepository, never()).completeProcessingRefund(any(), any());
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
}
