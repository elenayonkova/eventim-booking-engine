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
import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.repository.PaymentRepository;
import com.eventim.booking.engine.payment.service.ConflictException;
import com.eventim.booking.engine.payment.service.PaymentService;

@Testcontainers(disabledWithoutDocker = true)
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
}
