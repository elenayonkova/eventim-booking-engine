package com.eventim.booking.engine.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.eventim.booking.engine.payment.api.PaymentRequest;
import com.eventim.booking.engine.payment.api.PaymentResponse;
import com.eventim.booking.engine.payment.api.PaymentCancellationRequest;
import com.eventim.booking.engine.payment.api.PaymentCancellationResponse;
import com.eventim.booking.engine.payment.api.RefundRequest;
import com.eventim.booking.engine.payment.api.RefundResponse;
import com.eventim.booking.engine.payment.domain.PaymentIntentStatus;
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.service.ConflictException;
import com.eventim.booking.engine.payment.service.PaymentService;

@Testcontainers
@SpringBootTest
class PaymentServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eventim")
            .withUsername("eventim")
            .withPassword("eventim");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=payment");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    PaymentService paymentService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void resetPaymentData() {
        jdbc.update("delete from payment.refunds");
        jdbc.update("delete from payment.payments");
        jdbc.update("delete from payment.payment_intents");
    }

    @Test
    void concurrentFirstPaymentsReturnTheSamePayment() throws Exception {
        UUID reservationId = UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(reservationId, 10_000, "eur", "pm-test");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<PaymentResponse> attempt = () -> {
            ready.countDown();
            start.await();
            return paymentService.createPayment(request, null, null);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentResponse> first = executor.submit(attempt);
            Future<PaymentResponse> second = executor.submit(attempt);
            ready.await();
            start.countDown();

            List<PaymentResponse> responses = List.of(first.get(), second.get());
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.reservationId()).isEqualTo(reservationId);
                assertThat(response.paymentId()).isEqualTo(responses.get(0).paymentId());
                assertThat(response.amount()).isEqualTo(10_000);
                assertThat(response.currency()).isEqualTo("EUR");
                assertThat(response.paymentMethodFingerprint()).isNotBlank();
                assertThat(response.status()).isIn(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED);
            });
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from payment.payments where reservation_id = ?",
                Integer.class,
                reservationId)).isEqualTo(1);
        assertThat(paymentService.getPayment(reservationId).status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void repeatedPaymentWithDifferentPayloadIsRejected() {
        UUID reservationId = UUID.randomUUID();
        paymentService.createPayment(
                new PaymentRequest(reservationId, 10_000, "EUR", "pm-one"), null, null);

        assertThatThrownBy(() -> paymentService.createPayment(
                new PaymentRequest(reservationId, 12_000, "EUR", "pm-two"), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void staleRecoveryCannotBeOverwrittenByLatePaymentCompletion() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        paymentRepository.insertPayment(
                paymentId,
                reservationId,
                10_000,
                "EUR",
                "fingerprint",
                PaymentStatus.PROCESSING,
                null);

        paymentRepository.failStaleProcessingPayments(Duration.ZERO);
        var result = paymentRepository.completeProcessingPayment(
                paymentId,
                PaymentStatus.SUCCEEDED,
                null);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentService.getPayment(reservationId).status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void staleRecoveryCannotBeOverwrittenByLateRefundCompletion() {
        UUID reservationId = UUID.randomUUID();
        PaymentResponse payment = paymentService.createPayment(
                new PaymentRequest(reservationId, 10_000, "EUR", "pm-refund"),
                null,
                null);
        UUID refundId = UUID.randomUUID();
        paymentRepository.insertRefundIfAbsent(
                refundId,
                reservationId,
                payment.paymentId(),
                RefundStatus.PROCESSING);

        paymentRepository.failStaleProcessingRefunds(Duration.ZERO);
        var lateCompletion = paymentRepository.completeProcessingRefund(
                refundId,
                RefundStatus.SUCCEEDED);

        assertThat(lateCompletion.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(paymentService.getPayment(reservationId).status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void cancellationTombstoneRejectsALatePaymentRequest() {
        UUID reservationId = UUID.randomUUID();

        PaymentCancellationResponse cancellation = paymentService.cancelPayment(
                new PaymentCancellationRequest(reservationId),
                null,
                null);

        assertThat(cancellation.reservationId()).isEqualTo(reservationId);
        assertThat(cancellation.payment()).isNull();
        assertThat(jdbc.queryForObject(
                "select status from payment.payment_intents where reservation_id = ?",
                String.class,
                reservationId)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> paymentService.createPayment(
                new PaymentRequest(reservationId, 10_000, "EUR", "pm-late"),
                null,
                null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancelled");
        assertThat(jdbc.queryForObject(
                "select count(*) from payment.payments where reservation_id = ?",
                Integer.class,
                reservationId)).isZero();
    }

    @Test
    void cancellationPreventsAProcessingPaymentFromCompletingLate() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        paymentRepository.insertPayment(
                paymentId,
                reservationId,
                10_000,
                "EUR",
                "fingerprint",
                PaymentStatus.PROCESSING,
                null);

        PaymentCancellationResponse cancellation = paymentService.cancelPayment(
                new PaymentCancellationRequest(reservationId),
                null,
                null);
        var lateCompletion = paymentRepository.completeProcessingPayment(
                paymentId,
                PaymentStatus.SUCCEEDED,
                null);

        assertThat(cancellation.payment()).isNotNull();
        assertThat(cancellation.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(lateCompletion.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentService.getPayment(reservationId).status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void cancellationDelayHappensAfterPendingIntentIsCommitted() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        paymentRepository.insertPayment(
                paymentId,
                reservationId,
                10_000,
                "EUR",
                "fingerprint",
                PaymentStatus.PROCESSING,
                null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentCancellationResponse> future = executor.submit(
                    () -> paymentService.cancelPayment(
                            new PaymentCancellationRequest(reservationId),
                            1_000L,
                            null));

            awaitPaymentIntentStatus(reservationId, PaymentIntentStatus.CANCELLATION_PENDING);
            assertThat(paymentService.getPayment(reservationId).status())
                    .isEqualTo(PaymentStatus.PROCESSING);

            PaymentCancellationResponse completed = future.get();
            assertThat(completed.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(jdbc.queryForObject(
                    "select status from payment.payment_intents where reservation_id = ?",
                    String.class,
                    reservationId)).isEqualTo("CANCELLED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedCancellationRecordsPaymentThatWonTheRace() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        paymentRepository.insertPayment(
                paymentId,
                reservationId,
                10_000,
                "EUR",
                "fingerprint",
                PaymentStatus.PROCESSING,
                null);

        PaymentCancellationResponse cancellation = paymentService.cancelPayment(
                new PaymentCancellationRequest(reservationId),
                null,
                "true");

        assertThat(cancellation.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject(
                "select status from payment.payment_intents where reservation_id = ?",
                String.class,
                reservationId)).isEqualTo("ACTIVE");
    }

    @Test
    void excessiveSimulationDelayIsRejectedBeforePaymentIsCreated() {
        UUID reservationId = UUID.randomUUID();

        assertThatThrownBy(() -> paymentService.createPayment(
                new PaymentRequest(reservationId, 10_000, "EUR", "pm-test"),
                PaymentService.MAX_SIMULATED_DELAY_MS + 1,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Simulation delay");

        assertThat(jdbc.queryForObject(
                "select count(*) from payment.payments where reservation_id = ?",
                Integer.class,
                reservationId)).isZero();
    }

    @Test
    void refundRejectsFailedAndProcessingPayments() {
        UUID failedReservationId = UUID.randomUUID();
        paymentService.createPayment(
                new PaymentRequest(failedReservationId, 10_000, "EUR", "pm-failed"),
                null,
                "true");

        UUID processingReservationId = UUID.randomUUID();
        paymentRepository.insertPayment(
                UUID.randomUUID(),
                processingReservationId,
                10_000,
                "EUR",
                "fingerprint",
                PaymentStatus.PROCESSING,
                null);

        assertThatThrownBy(() -> paymentService.refund(
                new RefundRequest(failedReservationId), null, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> paymentService.refund(
                new RefundRequest(processingReservationId), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void refundDelayHappensAfterProcessingRefundIsCommitted() throws Exception {
        UUID reservationId = UUID.randomUUID();
        paymentService.createPayment(
                new PaymentRequest(reservationId, 10_000, "EUR", "pm-refund"),
                null,
                null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RefundResponse> future = executor.submit(() -> paymentService.refund(
                    new RefundRequest(reservationId),
                    1_000L,
                    null));

            awaitRefundStatus(reservationId, RefundStatus.PROCESSING);
            assertThat(paymentService.getPayment(reservationId).status())
                    .isEqualTo(PaymentStatus.SUCCEEDED);

            RefundResponse completed = future.get();
            assertThat(completed.status()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(paymentService.getPayment(reservationId).status())
                    .isEqualTo(PaymentStatus.REFUNDED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedRefundIsDurableAndDoesNotMarkPaymentRefunded() {
        UUID reservationId = UUID.randomUUID();
        paymentService.createPayment(
                new PaymentRequest(reservationId, 10_000, "EUR", "pm-refund"),
                null,
                null);

        RefundResponse failed = paymentService.refund(
                new RefundRequest(reservationId),
                null,
                "true");
        RefundResponse repeated = paymentService.refund(
                new RefundRequest(reservationId), null, null);

        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(repeated).isEqualTo(failed);
        assertThat(paymentService.getPayment(reservationId).status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void excessiveRefundSimulationDelayIsRejectedBeforeRefundIsCreated() {
        UUID reservationId = UUID.randomUUID();
        paymentService.createPayment(
                new PaymentRequest(reservationId, 10_000, "EUR", "pm-refund"),
                null,
                null);

        assertThatThrownBy(() -> paymentService.refund(
                new RefundRequest(reservationId),
                PaymentService.MAX_SIMULATED_DELAY_MS + 1,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Simulation delay");
        assertThat(jdbc.queryForObject(
                "select count(*) from payment.refunds where reservation_id = ?",
                Integer.class,
                reservationId)).isZero();
    }

    private void awaitRefundStatus(UUID reservationId, RefundStatus expectedStatus)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> statuses = jdbc.queryForList(
                    "select status from payment.refunds where reservation_id = ?",
                    String.class,
                    reservationId);
            if (statuses.contains(expectedStatus.name())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Refund did not reach " + expectedStatus);
    }

    private void awaitPaymentIntentStatus(
            UUID reservationId,
            PaymentIntentStatus expectedStatus
    ) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> statuses = jdbc.queryForList(
                    "select status from payment.payment_intents where reservation_id = ?",
                    String.class,
                    reservationId);
            if (statuses.contains(expectedStatus.name())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Payment intent did not reach " + expectedStatus);
    }
}
