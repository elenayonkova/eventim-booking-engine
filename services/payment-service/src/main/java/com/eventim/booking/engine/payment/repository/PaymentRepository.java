package com.eventim.booking.engine.payment.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.PaymentIntentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;

/**
 * JDBC persistence gateway for payment intents, payments, and refunds. It
 * exposes lock-aware and idempotent updates intended to run inside service
 * transaction boundaries.
 */
@Repository
public class PaymentRepository {

    private static final RowMapper<PaymentRecord> PAYMENT_ROW_MAPPER = new RowMapper<PaymentRecord>() {
        @Override
        public PaymentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PaymentRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("reservation_id", UUID.class),
                    rs.getLong("amount"),
                    rs.getString("currency"),
                    rs.getString("payment_method_fingerprint"),
                    PaymentStatus.valueOf(rs.getString("status")),
                    rs.getString("failure_reason"));
        }
    };

    private static final RowMapper<RefundRecord> REFUND_ROW_MAPPER = new RowMapper<RefundRecord>() {
        @Override
        public RefundRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RefundRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("reservation_id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    RefundStatus.valueOf(rs.getString("status")));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PaymentRecord> findPaymentByReservationId(UUID reservationId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    """
                    select id, reservation_id, amount, currency, payment_method_fingerprint, status, failure_reason
                    from payments
                    where reservation_id = :reservationId
                    """,
                    Map.of("reservationId", reservationId),
                    PAYMENT_ROW_MAPPER));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public PaymentIntentStatus lockOrCreatePaymentIntent(
            UUID reservationId,
            PaymentIntentStatus initialStatus
    ) {
        jdbc.update(
                """
                insert into payment_intents (reservation_id, status)
                values (:reservationId, :status)
                on conflict (reservation_id) do nothing
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("status", initialStatus.name()));
        return lockPaymentIntent(reservationId);
    }

    public PaymentIntentStatus lockPaymentIntent(UUID reservationId) {
        String status = jdbc.queryForObject(
                """
                select status
                from payment_intents
                where reservation_id = :reservationId
                for update
                """,
                Map.of("reservationId", reservationId),
                String.class);
        return PaymentIntentStatus.valueOf(status);
    }

    public void updatePaymentIntentStatus(
            UUID reservationId,
            PaymentIntentStatus status
    ) {
        jdbc.update(
                """
                update payment_intents
                set status = :status,
                    updated_at = now()
                where reservation_id = :reservationId
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("status", status.name()));
    }

    public Optional<PaymentRecord> findPaymentByReservationIdForUpdate(UUID reservationId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    """
                    select id, reservation_id, amount, currency, payment_method_fingerprint, status, failure_reason
                    from payments
                    where reservation_id = :reservationId
                    for update
                    """,
                    Map.of("reservationId", reservationId),
                    PAYMENT_ROW_MAPPER));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<RefundRecord> findRefundByReservationId(UUID reservationId) {
        return findRefundByReservationId(reservationId, false);
    }

    public Optional<RefundRecord> findRefundByReservationIdForUpdate(UUID reservationId) {
        return findRefundByReservationId(reservationId, true);
    }

    private Optional<RefundRecord> findRefundByReservationId(
            UUID reservationId,
            boolean forUpdate
    ) {
        try {
            String lockClause = forUpdate ? " for update" : "";
            return Optional.of(jdbc.queryForObject(
                    "select id, reservation_id, payment_id, status "
                            + "from refunds "
                            + "where reservation_id = :reservationId"
                            + lockClause,
                    Map.of("reservationId", reservationId),
                    REFUND_ROW_MAPPER));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<PaymentRecord> insertPaymentIfAbsent(
            UUID paymentId,
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodFingerprint,
            PaymentStatus status,
            String failureReason
    ) {
        List<PaymentRecord> inserted = jdbc.query(
                """
                insert into payments (
                    id,
                    reservation_id,
                    amount,
                    currency,
                    payment_method_fingerprint,
                    status,
                    failure_reason
                )
                values (
                    :id,
                    :reservationId,
                    :amount,
                    :currency,
                    :paymentMethodFingerprint,
                    :status,
                    :failureReason
                )
                on conflict (reservation_id) do nothing
                returning id, reservation_id, amount, currency, payment_method_fingerprint, status, failure_reason
                """,
                paymentParams(paymentId, reservationId, amount, currency, paymentMethodFingerprint, status, failureReason),
                PAYMENT_ROW_MAPPER);

        return firstPayment(inserted);
    }

    public PaymentRecord insertPayment(
            UUID paymentId,
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodFingerprint,
            PaymentStatus status,
            String failureReason
    ) {
        PaymentIntentStatus intent = lockOrCreatePaymentIntent(
                reservationId,
                PaymentIntentStatus.ACTIVE);
        if (intent != PaymentIntentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Payment intent is not active for reservation " + reservationId);
        }
        Optional<PaymentRecord> inserted = insertPaymentIfAbsent(
                paymentId,
                reservationId,
                amount,
                currency,
                paymentMethodFingerprint,
                status,
                failureReason);
        if (inserted.isEmpty()) {
            throw new IllegalStateException("Payment already exists for reservation " + reservationId);
        }

        return inserted.get();
    }

    public Optional<RefundRecord> insertRefundIfAbsent(UUID refundId, UUID reservationId, UUID paymentId, RefundStatus status) {
        List<RefundRecord> inserted = jdbc.query(
                """
                insert into refunds (id, reservation_id, payment_id, status)
                values (:id, :reservationId, :paymentId, :status)
                on conflict (reservation_id) do nothing
                returning id, reservation_id, payment_id, status
                """,
                new MapSqlParameterSource()
                        .addValue("id", refundId)
                        .addValue("reservationId", reservationId)
                        .addValue("paymentId", paymentId)
                        .addValue("status", status.name()),
                REFUND_ROW_MAPPER);

        return firstRefund(inserted);
    }

    public void markPaymentRefunded(UUID paymentId) {
        updatePaymentStatus(paymentId, PaymentStatus.REFUNDED, null);
    }

    public RefundRecord completeProcessingRefund(UUID refundId, RefundStatus status) {
        jdbc.update(
                """
                update refunds
                set status = :status,
                    updated_at = now()
                where id = :refundId
                  and status = 'PROCESSING'
                """,
                new MapSqlParameterSource()
                        .addValue("refundId", refundId)
                        .addValue("status", status.name()));
        return findRefundById(refundId);
    }

    public PaymentRecord updatePaymentStatus(
            UUID paymentId,
            PaymentStatus status,
            String failureReason
    ) {
        jdbc.update(
                """
                update payments
                set status = :status,
                    failure_reason = :failureReason,
                    updated_at = now()
                where id = :paymentId
                """,
                new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("status", status.name())
                        .addValue("failureReason", failureReason));
        return findPaymentById(paymentId);
    }

    public PaymentRecord completeProcessingPayment(
            UUID paymentId,
            PaymentStatus status,
            String failureReason
    ) {
        jdbc.update(
                """
                update payments
                set status = :status,
                    failure_reason = :failureReason,
                    updated_at = now()
                where id = :paymentId
                  and status = 'PROCESSING'
                """,
                new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("status", status.name())
                        .addValue("failureReason", failureReason));
        return findPaymentById(paymentId);
    }

    public int failStaleProcessingPayments(java.time.Duration timeout) {
        return jdbc.update(
                """
                update payments
                set status = 'FAILED',
                    failure_reason = 'Payment processing was interrupted',
                    updated_at = now()
                where status = 'PROCESSING'
                  and updated_at <= now() - (:timeoutSeconds * interval '1 second')
                """,
                Map.of("timeoutSeconds", timeout.toSeconds()));
    }

    public int finalizeFailedCancellationIntents() {
        return jdbc.update(
                """
                update payment_intents intent
                set status = 'CANCELLED',
                    updated_at = now()
                where intent.status = 'CANCELLATION_PENDING'
                  and exists (
                      select 1
                      from payments payment
                      where payment.reservation_id = intent.reservation_id
                        and payment.status = 'FAILED'
                  )
                """,
                Map.of());
    }

    public int failStaleProcessingRefunds(java.time.Duration timeout) {
        return jdbc.update(
                """
                update refunds
                set status = 'FAILED',
                    updated_at = now()
                where status = 'PROCESSING'
                  and updated_at <= now() - (:timeoutSeconds * interval '1 second')
                """,
                Map.of("timeoutSeconds", timeout.toSeconds()));
    }

    private PaymentRecord findPaymentById(UUID paymentId) {
        return jdbc.queryForObject(
                """
                select id, reservation_id, amount, currency, payment_method_fingerprint, status, failure_reason
                from payments
                where id = :paymentId
                """,
                Map.of("paymentId", paymentId),
                PAYMENT_ROW_MAPPER);
    }

    private RefundRecord findRefundById(UUID refundId) {
        return jdbc.queryForObject(
                """
                select id, reservation_id, payment_id, status
                from refunds
                where id = :refundId
                """,
                Map.of("refundId", refundId),
                REFUND_ROW_MAPPER);
    }

    private MapSqlParameterSource paymentParams(
            UUID paymentId,
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodFingerprint,
            PaymentStatus status,
            String failureReason
    ) {
        return new MapSqlParameterSource()
                .addValue("id", paymentId)
                .addValue("reservationId", reservationId)
                .addValue("amount", amount)
                .addValue("currency", currency)
                .addValue("paymentMethodFingerprint", paymentMethodFingerprint)
                .addValue("status", status.name())
                .addValue("failureReason", failureReason);
    }

    private Optional<PaymentRecord> firstPayment(List<PaymentRecord> payments) {
        if (payments.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(payments.get(0));
    }

    private Optional<RefundRecord> firstRefund(List<RefundRecord> refunds) {
        if (refunds.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(refunds.get(0));
    }
}
