package com.eventim.booking.engine.booking.service.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.domain.SeatStatus;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentStatus;
import com.eventim.booking.engine.booking.repository.BookingRepository;
import com.eventim.booking.engine.booking.repository.ReservationRow;
import com.eventim.booking.engine.booking.repository.ReservationSeatRow;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

@ExtendWith(MockitoExtension.class)
class ReservationCheckoutTest {

    private static final long AMOUNT = 5_000;
    private static final String CURRENCY = "EUR";
    private static final String TOKEN_DIGEST = "token-digest";

    @Mock
    BookingRepository bookingRepository;

    ReservationCheckout reservationCheckout;

    @BeforeEach
    void setUp() {
        reservationCheckout = new ReservationCheckout(bookingRepository);
    }

    @Test
    void heldCheckoutUsesTheReservationPriceSnapshot() {
        UUID reservationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        ReservationRow reservation = heldReservation(reservationId, now.plusMinutes(5));
        when(bookingRepository.lockReservation(reservationId)).thenReturn(reservation);
        when(bookingRepository.databaseNow()).thenReturn(now);
        when(bookingRepository.lockReservationSeats(reservationId)).thenReturn(List.of(
                heldSeat("A-1", reservationId),
                heldSeat("A-2", reservationId)));

        CheckoutSnapshot checkout = reservationCheckout.beginCheckout(
                reservationId,
                TOKEN_DIGEST);

        assertThat(checkout.amount()).isEqualTo(AMOUNT);
        assertThat(checkout.currency()).isEqualTo(CURRENCY);
        assertThat(checkout.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        verify(bookingRepository).markPaymentPending(
                reservationId,
                TOKEN_DIGEST);
    }

    @Test
    void successfulPaymentForReservationThatLostASeatRequiresRefund() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        ReservationRow reservation = checkoutReservation(
                reservationId,
                paymentId,
                ReservationStatus.PAYMENT_PENDING,
                OffsetDateTime.now());
        CheckoutSnapshot checkout = CheckoutSnapshot.from(reservation);
        PaymentResult payment = payment(paymentId, reservationId, PaymentStatus.SUCCEEDED);
        when(bookingRepository.lockReservation(reservationId)).thenReturn(reservation);
        when(bookingRepository.lockReservationSeats(reservationId)).thenReturn(List.of(
                new ReservationSeatRow(
                        UUID.randomUUID(),
                        "A-1",
                        SeatStatus.AVAILABLE,
                        null)));

        CheckoutSnapshot result = reservationCheckout.applyPaymentResult(checkout, payment);

        assertThat(result.status()).isEqualTo(ReservationStatus.REFUND_REQUIRED);
        assertThat(result.paymentId()).isEqualTo(paymentId);
        verify(bookingRepository).markRefundRequiredAndReleaseSeats(
                reservationId,
                paymentId,
                "Paid reservation no longer owns every held seat");
    }

    @Test
    void paymentForDifferentReservationIsRejectedBeforeStoredStateIsRead() {
        UUID reservationId = UUID.randomUUID();
        CheckoutSnapshot checkout = pendingCheckout(reservationId);
        PaymentResult payment = payment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PaymentStatus.SUCCEEDED);

        assertThatThrownBy(() -> reservationCheckout.applyPaymentResult(checkout, payment))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("another reservation");

        verifyNoInteractions(bookingRepository);
    }

    @Test
    void processingResultAfterReservationBecameBookedReturnsStoredTerminalState() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        CheckoutSnapshot checkout = pendingCheckout(reservationId);
        ReservationRow booked = checkoutReservation(
                reservationId,
                paymentId,
                ReservationStatus.BOOKED,
                OffsetDateTime.now());
        when(bookingRepository.lockReservation(reservationId)).thenReturn(booked);

        CheckoutSnapshot result = reservationCheckout.applyPaymentResult(
                checkout,
                payment(paymentId, reservationId, PaymentStatus.PROCESSING));

        assertThat(result.status()).isEqualTo(ReservationStatus.BOOKED);
        verify(bookingRepository).lockReservation(reservationId);
        verifyNoMoreInteractions(bookingRepository);
    }

    @Test
    void processingPaymentRemainsPending() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        CheckoutSnapshot checkout = pendingCheckout(reservationId);
        ReservationRow pending = checkoutReservation(
                reservationId,
                null,
                ReservationStatus.PAYMENT_PENDING,
                OffsetDateTime.now());
        when(bookingRepository.lockReservation(reservationId)).thenReturn(pending);

        CheckoutSnapshot result = reservationCheckout.applyPaymentResult(
                checkout,
                payment(paymentId, reservationId, PaymentStatus.PROCESSING));

        assertThat(result.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(result.paymentId()).isEqualTo(paymentId);
        verify(bookingRepository).recordProcessingPayment(reservationId, paymentId);
    }

    @Test
    void paymentWithDifferentIdThanTheRecordedPaymentIsRejected() {
        UUID reservationId = UUID.randomUUID();
        UUID recordedPaymentId = UUID.randomUUID();
        ReservationRow pending = checkoutReservation(
                reservationId,
                recordedPaymentId,
                ReservationStatus.PAYMENT_PENDING,
                OffsetDateTime.now());
        CheckoutSnapshot checkout = CheckoutSnapshot.from(pending);
        when(bookingRepository.lockReservation(reservationId)).thenReturn(pending);

        assertThatThrownBy(() -> reservationCheckout.applyPaymentResult(
                checkout,
                payment(UUID.randomUUID(), reservationId, PaymentStatus.SUCCEEDED)))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("different payment");

        verify(bookingRepository).lockReservation(reservationId);
        verifyNoMoreInteractions(bookingRepository);
    }

    private ReservationRow heldReservation(UUID reservationId, OffsetDateTime expiresAt) {
        return new ReservationRow(
                reservationId,
                "event-1",
                ReservationStatus.HELD,
                expiresAt,
                null,
                AMOUNT,
                CURRENCY,
                null,
                null);
    }

    private ReservationRow checkoutReservation(
            UUID reservationId,
            UUID paymentId,
            ReservationStatus status,
            OffsetDateTime stateTime
    ) {
        return new ReservationRow(
                reservationId,
                "event-1",
                status,
                stateTime.plusMinutes(5),
                paymentId,
                AMOUNT,
                CURRENCY,
                TOKEN_DIGEST,
                null);
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

    private ReservationSeatRow heldSeat(String label, UUID reservationId) {
        return new ReservationSeatRow(
                UUID.randomUUID(),
                label,
                SeatStatus.HELD,
                reservationId);
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
