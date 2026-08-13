package com.eventim.booking.engine.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.eventim.booking.engine.booking.api.CheckoutRequest;
import com.eventim.booking.engine.booking.api.CheckoutResponse;
import com.eventim.booking.engine.booking.api.CreateReservationRequest;
import com.eventim.booking.engine.booking.api.ReservationResponse;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.payment.ChargePayment;
import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.PaymentCancellationResult;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentSimulation;
import com.eventim.booking.engine.booking.payment.PaymentStatus;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.payment.RefundStatus;
import com.eventim.booking.engine.booking.service.BookingService;
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.ExternalServiceException;
import com.eventim.booking.engine.booking.service.checkout.CheckoutService;

@Testcontainers
@SpringBootTest(properties = "booking.hold-ttl=5m")
class BookingServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eventim")
            .withUsername("eventim")
            .withPassword("eventim");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=booking");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    BookingService bookingService;

    @Autowired
    CheckoutService checkoutService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    PaymentGateway paymentGateway;

    @AfterEach
    void resetBookingData() {
        jdbc.update("""
                update booking.seats
                set status = 'AVAILABLE',
                    reservation_id = null,
                    hold_expires_at = null,
                    version = 0
                """);
        jdbc.update("delete from booking.reservation_seats");
        jdbc.update("delete from booking.reservations");
    }

    @Test
    void onlyOneConcurrentReservationCanHoldTheSameSeat() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> attempt = () -> {
            ready.countDown();
            start.await();
            try {
                return bookingService.createReservation(
                        new CreateReservationRequest("event-1", List.of("A-1")));
            } catch (RuntimeException exception) {
                return exception;
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(attempt);
            Future<Object> second = executor.submit(attempt);
            ready.await();
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(ReservationResponse.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ConflictException.class::isInstance).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from booking.seats where seat_label = 'A-1' and status = 'HELD'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void overlappingMultiSeatReservationRollsBackWithoutPartialHold() {
        bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1", "A-2")));

        assertThatThrownBy(() -> bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-2", "A-3"))))
                .isInstanceOf(ConflictException.class);

        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-3'",
                String.class)).isEqualTo("AVAILABLE");
    }

    @Test
    void expiredReservationReleasesItsSeat() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        jdbc.update(
                "update booking.reservations set expires_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(1),
                reservation.reservationId());

        bookingService.getSeats("event-1");

        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("EXPIRED");
    }

    @Test
    void successfulCheckoutBooksEverySeatAndIsRetrySafe() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1", "A-2")));
        UUID paymentId = UUID.randomUUID();
        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        10_000,
                        "EUR",
                        "pm-test",
                        new PaymentSimulation(null, null))))
                .thenReturn(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        10_000,
                        "EUR",
                        fingerprint("pm-test"),
                        PaymentStatus.SUCCEEDED,
                        null));

        CheckoutResponse first = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "pm-test"), null, null);
        CheckoutResponse second = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "pm-test"), null, null);

        assertThat(first.status()).isEqualTo(ReservationStatus.BOOKED);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "select count(*) from booking.seats where reservation_id = ? and status = 'BOOKED'",
                Integer.class,
                reservation.reservationId())).isEqualTo(2);
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.times(1))
                .charge(new ChargePayment(
                        reservation.reservationId(),
                        10_000,
                        "EUR",
                        "pm-test",
                        new PaymentSimulation(null, null)));
    }

    @Test
    void failedCheckoutReleasesEverySeatAndIsRetrySafe() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1", "A-2")));
        UUID paymentId = UUID.randomUUID();
        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        10_000,
                        "EUR",
                        "declined",
                        new PaymentSimulation(null, "true"))))
                .thenReturn(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        10_000,
                        "EUR",
                        fingerprint("declined"),
                        PaymentStatus.FAILED,
                        "Simulated payment failure"));

        CheckoutResponse first = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "declined"), null, "true");
        CheckoutResponse second = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "declined"), null, "true");

        assertThat(first.status()).isEqualTo(ReservationStatus.PAYMENT_FAILED);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "select count(*) from booking.seats where seat_label in ('A-1', 'A-2') and status = 'AVAILABLE'",
                Integer.class)).isEqualTo(2);
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.times(1))
                .charge(new ChargePayment(
                        reservation.reservationId(),
                        10_000,
                        "EUR",
                        "declined",
                        new PaymentSimulation(null, "true")));
    }

    @Test
    void reconciliationCompletesAProcessingPaymentAfterTheClientDisconnects() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        UUID paymentId = UUID.randomUUID();
        PaymentResult processing = new PaymentResult(
                paymentId,
                reservation.reservationId(),
                5_000,
                "EUR",
                fingerprint("slow-payment"),
                PaymentStatus.PROCESSING,
                null);
        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        "slow-payment",
                        new PaymentSimulation(20_000L, null))))
                .thenReturn(processing);
        org.mockito.Mockito.when(paymentGateway.find(reservation.reservationId()))
                .thenReturn(Optional.of(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        fingerprint("slow-payment"),
                        PaymentStatus.SUCCEEDED,
                        null)));

        CheckoutResponse pending = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "slow-payment"), 20_000L, null);
        checkoutService.reconcilePendingPayments();

        assertThat(pending.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("BOOKED");
    }

    @Test
    void reconciliationRefundsSuccessfulPaymentWithMismatchedPayload() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        UUID paymentId = UUID.randomUUID();

        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        "pm-original",
                        new PaymentSimulation(null, null))))
                .thenReturn(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        fingerprint("pm-original"),
                        PaymentStatus.PROCESSING,
                        null));
        org.mockito.Mockito.when(paymentGateway.find(reservation.reservationId()))
                .thenReturn(Optional.of(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        1,
                        "EUR",
                        fingerprint("pm-other"),
                        PaymentStatus.SUCCEEDED,
                        null)));
        org.mockito.Mockito.when(paymentGateway.refund(reservation.reservationId()))
                .thenThrow(new ExternalServiceException("Temporary refund failure"))
                .thenReturn(new RefundResult(
                        UUID.randomUUID(),
                        reservation.reservationId(),
                        paymentId,
                        RefundStatus.SUCCEEDED));

        checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "pm-original"), null, null);
        checkoutService.reconcilePendingPayments();

        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("AVAILABLE");
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.times(2))
                .refund(reservation.reservationId());
    }

    @Test
    void refundForAnotherReservationLeavesRefundPending() {
        assertInvalidRefundLeavesRecoveryPending((reservationId, paymentId) -> new RefundResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                paymentId,
                RefundStatus.SUCCEEDED));
    }

    @Test
    void refundForAnotherPaymentLeavesRefundPending() {
        assertInvalidRefundLeavesRecoveryPending((reservationId, paymentId) -> new RefundResult(
                UUID.randomUUID(),
                reservationId,
                UUID.randomUUID(),
                RefundStatus.SUCCEEDED));
    }

    @Test
    void refundWithoutIdentifiersLeavesRefundPending() {
        assertInvalidRefundLeavesRecoveryPending((reservationId, paymentId) -> new RefundResult(
                null,
                null,
                null,
                RefundStatus.SUCCEEDED));
    }

    @Test
    void excessiveSimulationDelayIsRejectedBeforeCheckoutStarts() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));

        assertThatThrownBy(() -> checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "pm-test"),
                PaymentSimulation.MAX_DELAY_MS + 1,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Simulation delay");

        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("HELD");
        org.mockito.Mockito.verifyNoInteractions(paymentGateway);
    }

    @Test
    void checkoutAfterHoldExpiryFailsAndReleasesSeat() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        jdbc.update(
                "update booking.reservations set expires_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(1),
                reservation.reservationId());

        assertThatThrownBy(() -> checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "pm-test"), null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Reservation has expired");

        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("AVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(paymentGateway);
    }

    @Test
    void refundedPaymentResultReleasesSeatsAndIsRetrySafe() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        UUID paymentId = UUID.randomUUID();
        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        "already-refunded",
                        new PaymentSimulation(null, null))))
                .thenReturn(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        fingerprint("already-refunded"),
                        PaymentStatus.REFUNDED,
                        null));

        CheckoutResponse first = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "already-refunded"), null, null);
        CheckoutResponse second = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "already-refunded"), null, null);

        assertThat(first.status()).isEqualTo(ReservationStatus.REFUNDED);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("AVAILABLE");
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.times(1))
                .charge(new ChargePayment(
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        "already-refunded",
                        new PaymentSimulation(null, null)));
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.never())
                .refund(reservation.reservationId());
    }

    @Test
    void abandonedPendingPaymentTimesOutAndReleasesSeat() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        UUID paymentId = UUID.randomUUID();
        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        "lost-payment",
                        new PaymentSimulation(null, null))))
                .thenReturn(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        fingerprint("lost-payment"),
                        PaymentStatus.PROCESSING,
                        null));
        org.mockito.Mockito.when(paymentGateway.find(reservation.reservationId()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.when(paymentGateway.cancel(reservation.reservationId()))
                .thenReturn(new PaymentCancellationResult(reservation.reservationId(), null));

        CheckoutResponse pending = checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "lost-payment"), null, null);
        jdbc.update(
                "update booking.reservations set checkout_started_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(10),
                reservation.reservationId());

        checkoutService.reconcilePendingPayments();

        assertThat(pending.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("PAYMENT_FAILED");
        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("AVAILABLE");
        org.mockito.Mockito.verify(paymentGateway).cancel(reservation.reservationId());
    }

    @Test
    void reconciliationAppliesAPaymentThatWinsTheTimeoutCancellationRace() {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        UUID paymentId = UUID.randomUUID();
        PaymentResult processing = new PaymentResult(
                paymentId,
                reservation.reservationId(),
                5_000,
                "EUR",
                fingerprint("racing-payment"),
                PaymentStatus.PROCESSING,
                null);
        PaymentResult succeeded = new PaymentResult(
                paymentId,
                reservation.reservationId(),
                5_000,
                "EUR",
                fingerprint("racing-payment"),
                PaymentStatus.SUCCEEDED,
                null);
        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        "racing-payment",
                        new PaymentSimulation(null, null))))
                .thenReturn(processing);
        org.mockito.Mockito.when(paymentGateway.find(reservation.reservationId()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.when(paymentGateway.cancel(reservation.reservationId()))
                .thenReturn(new PaymentCancellationResult(reservation.reservationId(), succeeded));

        checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "racing-payment"), null, null);
        jdbc.update(
                "update booking.reservations set checkout_started_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(10),
                reservation.reservationId());

        checkoutService.reconcilePendingPayments();

        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("BOOKED");
    }

    private void assertInvalidRefundLeavesRecoveryPending(
            java.util.function.BiFunction<UUID, UUID, RefundResult> invalidRefund
    ) {
        ReservationResponse reservation = bookingService.createReservation(
                new CreateReservationRequest("event-1", List.of("A-1")));
        UUID paymentId = UUID.randomUUID();
        org.mockito.Mockito.when(paymentGateway.charge(new ChargePayment(
                        reservation.reservationId(),
                        5_000,
                        "EUR",
                        "refund-validation",
                        new PaymentSimulation(null, null))))
                .thenReturn(new PaymentResult(
                        paymentId,
                        reservation.reservationId(),
                        1,
                        "EUR",
                        fingerprint("refund-validation"),
                        PaymentStatus.SUCCEEDED,
                        null));
        org.mockito.Mockito.when(paymentGateway.refund(reservation.reservationId()))
                .thenReturn(invalidRefund.apply(reservation.reservationId(), paymentId));

        assertThatThrownBy(() -> checkoutService.checkout(
                new CheckoutRequest(reservation.reservationId(), "refund-validation"),
                null,
                null))
                .isInstanceOf(ExternalServiceException.class);

        assertThat(jdbc.queryForObject(
                "select status from booking.reservations where id = ?",
                String.class,
                reservation.reservationId())).isEqualTo("REFUND_REQUIRED");
        assertThat(jdbc.queryForObject(
                "select status from booking.seats where seat_label = 'A-1'",
                String.class)).isEqualTo("AVAILABLE");
    }

    private String fingerprint(String paymentMethodToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(paymentMethodToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
