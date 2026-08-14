package com.eventim.booking.engine.booking.service.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

@ExtendWith(MockitoExtension.class)
class ReservationCheckoutTest {

    private static final long AMOUNT = 5_000;
    private static final String CURRENCY = "EUR";
    private static final String FINGERPRINT = "fingerprint";

    @Mock
    BookingRepository bookingRepository;

    ReservationCheckout reservationCheckout;

    @BeforeEach
    void setUp() {
        reservationCheckout = new ReservationCheckout(bookingRepository);
    }

    @Test
    void checkoutWithSeatsInDifferentCurrenciesIsRejectedBeforePaymentStateIsStored() {
        UUID reservationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        ReservationRow reservation = heldReservation(reservationId, now.plusMinutes(5));
        when(bookingRepository.lockReservation(reservationId)).thenReturn(reservation);
        when(bookingRepository.databaseNow()).thenReturn(now);
        when(bookingRepository.lockReservationSeats(reservationId)).thenReturn(List.of(
                heldSeat("A-1", reservationId, 5_000, "EUR"),
                heldSeat("A-2", reservationId, 6_000, "USD")));

        assertThatThrownBy(() -> reservationCheckout.beginCheckout(reservationId, FINGERPRINT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different currencies");

        verify(bookingRepository, never()).markPaymentPending(
                any(), anyLong(), anyString(), anyString());
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
        CheckoutStep checkout = CheckoutStep.charge(reservation);
        PaymentResult payment = payment(
                paymentId,
                reservationId,
                PaymentStatus.SUCCEEDED);
        when(bookingRepository.lockReservation(reservationId)).thenReturn(reservation);
        when(bookingRepository.lockReservationSeats(reservationId)).thenReturn(List.of(
                new ReservationSeatRow(
                        UUID.randomUUID(),
                        "A-1",
                        SeatStatus.AVAILABLE,
                        null,
                        AMOUNT,
                        CURRENCY)));

        CheckoutStep result = reservationCheckout.applyPaymentResult(checkout, payment);

        assertThat(result.action()).isEqualTo(CheckoutStep.Action.REFUND);
        assertThat(result.reservationStatus()).isEqualTo(ReservationStatus.REFUND_REQUIRED);
        assertThat(result.paymentId()).isEqualTo(paymentId);
        verify(bookingRepository).markRefundRequiredAndReleaseSeats(
                reservationId,
                paymentId,
                "Paid reservation no longer owns every held seat");
    }

    @Test
    void paymentForDifferentReservationIsRejectedBeforeStoredStateIsRead() {
        UUID reservationId = UUID.randomUUID();
        CheckoutStep checkout = CheckoutStep.startedCharge(
                reservationId,
                AMOUNT,
                CURRENCY,
                FINGERPRINT);
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
        CheckoutStep checkout = CheckoutStep.startedCharge(
                reservationId,
                AMOUNT,
                CURRENCY,
                FINGERPRINT);
        ReservationRow booked = checkoutReservation(
                reservationId,
                paymentId,
                ReservationStatus.BOOKED,
                OffsetDateTime.now());
        when(bookingRepository.lockReservation(reservationId)).thenReturn(booked);

        CheckoutStep result = reservationCheckout.applyPaymentResult(
                checkout,
                payment(paymentId, reservationId, PaymentStatus.PROCESSING));

        assertThat(result.action()).isEqualTo(CheckoutStep.Action.RETURN);
        assertThat(result.reservationStatus()).isEqualTo(ReservationStatus.BOOKED);
        verify(bookingRepository).lockReservation(reservationId);
        verifyNoMoreInteractions(bookingRepository);
    }

    @Test
    void paymentPendingCheckoutIsTimedOutAtTheExactBoundary() {
        UUID reservationId = UUID.randomUUID();
        OffsetDateTime checkoutStartedAt = OffsetDateTime.now();
        Duration timeout = Duration.ofMinutes(3);
        ReservationRow reservation = checkoutReservation(
                reservationId,
                UUID.randomUUID(),
                ReservationStatus.PAYMENT_PENDING,
                checkoutStartedAt);
        when(bookingRepository.lockReservation(reservationId)).thenReturn(reservation);
        when(bookingRepository.databaseNow()).thenReturn(checkoutStartedAt.plus(timeout));

        CheckoutStep result = reservationCheckout.loadTimedOutPaymentPendingCheckout(
                reservationId,
                timeout);

        assertThat(result).isNotNull();
        assertThat(result.action()).isEqualTo(CheckoutStep.Action.CHARGE);
    }

    private ReservationRow heldReservation(UUID reservationId, OffsetDateTime expiresAt) {
        return new ReservationRow(
                reservationId,
                "event-1",
                ReservationStatus.HELD,
                expiresAt,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private ReservationRow checkoutReservation(
            UUID reservationId,
            UUID paymentId,
            ReservationStatus status,
            OffsetDateTime checkoutStartedAt
    ) {
        return new ReservationRow(
                reservationId,
                "event-1",
                status,
                checkoutStartedAt.plusMinutes(5),
                paymentId,
                AMOUNT,
                CURRENCY,
                FINGERPRINT,
                checkoutStartedAt,
                null);
    }

    private ReservationSeatRow heldSeat(
            String label,
            UUID reservationId,
            long amount,
            String currency
    ) {
        return new ReservationSeatRow(
                UUID.randomUUID(),
                label,
                SeatStatus.HELD,
                reservationId,
                amount,
                currency);
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
