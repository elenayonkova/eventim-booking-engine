package com.eventim.booking.engine.booking.service.checkout;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.eventim.booking.engine.booking.api.CheckoutRequest;
import com.eventim.booking.engine.booking.config.BookingProperties;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.payment.PaymentCancellationResult;
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
    private static final String FINGERPRINT = "fingerprint";

    @Mock
    BookingRepository bookingRepository;

    @Mock
    BookingProperties bookingProperties;

    @Mock
    PaymentGateway paymentGateway;

    @Mock
    ReservationCheckout reservationCheckout;

    CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(
                bookingRepository,
                bookingProperties,
                paymentGateway,
                reservationCheckout);
    }

    @Test
    void missingPaymentBeforeTimeoutIsLeftPendingWithoutCancellation() {
        UUID reservationId = UUID.randomUUID();
        Duration timeout = Duration.ofMinutes(3);
        CheckoutStep checkout = pendingCheckout(reservationId);
        when(bookingRepository.findPaymentPendingReservationIds()).thenReturn(List.of(reservationId));
        when(bookingRepository.findRefundRequiredReservationIds()).thenReturn(List.of());
        when(reservationCheckout.loadPaymentPendingCheckout(reservationId)).thenReturn(checkout);
        when(paymentGateway.find(reservationId)).thenReturn(Optional.empty());
        when(bookingProperties.paymentPendingTimeout()).thenReturn(timeout);
        when(reservationCheckout.loadTimedOutPaymentPendingCheckout(reservationId, timeout))
                .thenReturn(null);

        checkoutService.reconcilePendingPayments();

        verify(paymentGateway, never()).cancel(any());
        verify(reservationCheckout, never()).failPaymentAfterCancellation(any());
    }

    @Test
    void cancellationForAnotherReservationDoesNotFailThePendingCheckout() {
        UUID reservationId = UUID.randomUUID();
        Duration timeout = Duration.ofMinutes(3);
        CheckoutStep checkout = pendingCheckout(reservationId);
        when(bookingRepository.findPaymentPendingReservationIds()).thenReturn(List.of(reservationId));
        when(bookingRepository.findRefundRequiredReservationIds()).thenReturn(List.of());
        when(reservationCheckout.loadPaymentPendingCheckout(reservationId)).thenReturn(checkout);
        when(paymentGateway.find(reservationId)).thenReturn(Optional.empty());
        when(bookingProperties.paymentPendingTimeout()).thenReturn(timeout);
        when(reservationCheckout.loadTimedOutPaymentPendingCheckout(reservationId, timeout))
                .thenReturn(checkout);
        when(paymentGateway.cancel(reservationId)).thenReturn(
                new PaymentCancellationResult(UUID.randomUUID(), null));

        checkoutService.reconcilePendingPayments();

        verify(reservationCheckout, never()).failPaymentAfterCancellation(any());
        verify(reservationCheckout, never()).applyPaymentResult(any(), any());
    }

    @Test
    void reconciliationContinuesAfterOneReservationFails() {
        UUID failingReservationId = UUID.randomUUID();
        UUID succeedingReservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        CheckoutStep pending = pendingCheckout(succeedingReservationId);
        PaymentResult succeeded = payment(paymentId, succeedingReservationId, PaymentStatus.SUCCEEDED);
        CheckoutStep completed = pending.asResponse(paymentId, ReservationStatus.BOOKED, null);
        when(bookingRepository.findPaymentPendingReservationIds())
                .thenReturn(List.of(failingReservationId, succeedingReservationId));
        when(bookingRepository.findRefundRequiredReservationIds()).thenReturn(List.of());
        when(reservationCheckout.loadPaymentPendingCheckout(failingReservationId))
                .thenThrow(new ExternalServiceException("temporary failure"));
        when(reservationCheckout.loadPaymentPendingCheckout(succeedingReservationId))
                .thenReturn(pending);
        when(paymentGateway.find(succeedingReservationId)).thenReturn(Optional.of(succeeded));
        when(reservationCheckout.applyPaymentResult(pending, succeeded)).thenReturn(completed);

        checkoutService.reconcilePendingPayments();

        verify(reservationCheckout).applyPaymentResult(pending, succeeded);
    }

    @Test
    void unresolvedRefundIsNotAppliedToTheReservation() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        CheckoutStep refundRequired = CheckoutStep.refund(new ReservationRow(
                reservationId,
                "event-1",
                ReservationStatus.REFUND_REQUIRED,
                OffsetDateTime.now().plusMinutes(5),
                paymentId,
                AMOUNT,
                CURRENCY,
                FINGERPRINT,
                OffsetDateTime.now(),
                "refund required"));
        when(reservationCheckout.beginCheckout(eq(reservationId), anyString()))
                .thenReturn(refundRequired);
        when(paymentGateway.refund(reservationId)).thenReturn(new RefundResult(
                refundId,
                reservationId,
                paymentId,
                RefundStatus.PROCESSING));

        assertThatThrownBy(() -> checkoutService.checkout(
                new CheckoutRequest(reservationId, "pm-test"),
                null,
                null))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("unresolved");

        verify(reservationCheckout, never()).markRefunded(any(), any());
    }

    private CheckoutStep pendingCheckout(UUID reservationId) {
        return CheckoutStep.startedCharge(
                reservationId,
                AMOUNT,
                CURRENCY,
                FINGERPRINT);
    }

    private PaymentResult payment(UUID paymentId, UUID reservationId, PaymentStatus status) {
        return new PaymentResult(
                paymentId,
                reservationId,
                AMOUNT,
                CURRENCY,
                FINGERPRINT,
                status,
                null);
    }
}
