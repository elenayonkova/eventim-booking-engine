package com.eventim.booking.engine.booking.service.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.eventim.booking.engine.booking.api.CheckoutRequest;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.config.BookingProperties;
import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentStatus;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.payment.RefundStatus;
import com.eventim.booking.engine.booking.repository.BookingRepository;
import com.eventim.booking.engine.booking.repository.ReservationRow;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final long AMOUNT = 5_000;
    private static final String CURRENCY = "EUR";
    private static final String TOKEN_DIGEST = "token-digest";

    @Mock
    BookingRepository bookingRepository;

    @Mock
    PaymentGateway paymentGateway;

    @Mock
    ReservationCheckout reservationCheckout;

    @Mock
    BookingProperties bookingProperties;

    CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(
                bookingRepository,
                paymentGateway,
                reservationCheckout,
                bookingProperties);
    }

    @Test
    void processingProviderResultReturnsPendingWithoutChargingAgain() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        CheckoutRequest request = new CheckoutRequest(reservationId, "pm-test");
        CheckoutSnapshot pending = pendingCheckout(reservationId);
        PaymentResult processing = payment(paymentId, reservationId, PaymentStatus.PROCESSING);
        CheckoutSnapshot stored = pending.withPaymentResult(
                paymentId,
                ReservationStatus.PAYMENT_PENDING,
                null);
        when(reservationCheckout.beginCheckout(
                eq(reservationId),
                anyString()))
                .thenReturn(pending);
        when(paymentGateway.charge(any())).thenReturn(processing);
        when(reservationCheckout.applyPaymentResult(pending, processing)).thenReturn(stored);

        var response = checkoutService.completeCheckout(request, null, null);

        assertThat(response.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(response.paymentId()).isEqualTo(paymentId);
        verify(paymentGateway).charge(any());
        verify(paymentGateway, never()).refund(any());
    }

    @Test
    void unresolvedRefundIsNotAppliedToTheReservation() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        CheckoutSnapshot refundRequired = CheckoutSnapshot.from(new ReservationRow(
                reservationId,
                "event-1",
                ReservationStatus.REFUND_REQUIRED,
                OffsetDateTime.now().plusMinutes(5),
                paymentId,
                AMOUNT,
                CURRENCY,
                TOKEN_DIGEST,
                "refund required"));
        when(reservationCheckout.beginCheckout(
                eq(reservationId),
                anyString()))
                .thenReturn(refundRequired);
        when(paymentGateway.refund(reservationId)).thenReturn(new RefundResult(
                refundId,
                reservationId,
                paymentId,
                RefundStatus.PROCESSING));

        assertThatThrownBy(() -> checkoutService.completeCheckout(
                new CheckoutRequest(reservationId, "pm-test"),
                null,
                null))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("unresolved");

        verify(reservationCheckout, never()).markRefunded(any(), any());
    }

    @Test
    void missingPaymentReleasesInventoryWhileKeepingReconciliationPending() {
        UUID reservationId = UUID.randomUUID();
        CheckoutSnapshot pending = pendingCheckout(reservationId);
        Duration timeout = Duration.ofSeconds(90);
        when(bookingProperties.paymentMissingTimeout()).thenReturn(timeout);
        when(bookingRepository.findPaymentPendingReservationIds(timeout))
                .thenReturn(List.of(reservationId));
        when(reservationCheckout.loadPaymentPendingCheckout(reservationId)).thenReturn(pending);
        when(paymentGateway.findPayment(reservationId)).thenReturn(Optional.empty());

        checkoutService.reconcilePendingPayments();

        verify(reservationCheckout).releaseInventoryForMissingPayment(pending);
    }

    @Test
    void durablePaymentResultIsAppliedByReconciliation() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        CheckoutSnapshot pending = pendingCheckout(reservationId);
        PaymentResult succeeded = payment(paymentId, reservationId, PaymentStatus.SUCCEEDED);
        Duration timeout = Duration.ofSeconds(90);
        when(bookingProperties.paymentMissingTimeout()).thenReturn(timeout);
        when(bookingRepository.findPaymentPendingReservationIds(timeout))
                .thenReturn(List.of(reservationId));
        when(reservationCheckout.loadPaymentPendingCheckout(reservationId)).thenReturn(pending);
        when(paymentGateway.findPayment(reservationId)).thenReturn(Optional.of(succeeded));
        when(reservationCheckout.applyPaymentResult(pending, succeeded))
                .thenReturn(pending.withPaymentResult(
                        paymentId,
                        ReservationStatus.BOOKED,
                        null));

        checkoutService.reconcilePendingPayments();

        verify(reservationCheckout).applyPaymentResult(pending, succeeded);
        verify(reservationCheckout, never()).releaseInventoryForMissingPayment(any());
    }

    private CheckoutSnapshot pendingCheckout(UUID reservationId) {
        return new CheckoutSnapshot(
                reservationId,
                null,
                ReservationStatus.PAYMENT_PENDING,
                AMOUNT,
                CURRENCY,
                TOKEN_DIGEST,
                null);
    }

    private PaymentResult payment(UUID paymentId, UUID reservationId, PaymentStatus status) {
        return new PaymentResult(
                paymentId,
                reservationId,
                AMOUNT,
                CURRENCY,
                TOKEN_DIGEST,
                status,
                null);
    }
}
