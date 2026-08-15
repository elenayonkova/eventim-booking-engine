package com.eventim.booking.engine.payment.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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
import com.eventim.booking.engine.payment.domain.RefundStatus;

/**
 * JDBC persistence gateway for the reservation-scoped payment aggregate and
 * its refunds. Every payment mutation locks or conditionally updates one row.
 */
@Repository
public class PaymentRepository {

    private static final String PAYMENT_COLUMNS =
            "id, reservation_id, amount, currency, payment_method_token, payment_method_token_digest, "
                    + "status, attempt, failure_reason";
    private static final String REFUND_COLUMNS =
            "id, reservation_id, payment_id, status, attempt";

    private static final RowMapper<RefundRecord> REFUND_ROW_MAPPER = new RowMapper<RefundRecord>() {
        @Override
        public RefundRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RefundRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("reservation_id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    RefundStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempt"));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<PaymentRecord> paymentRowMapper;

    public PaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.paymentRowMapper = (rs, rowNum) -> {
            UUID reservationId = rs.getObject("reservation_id", UUID.class);
            return new PaymentRecord(
                    rs.getObject("id", UUID.class),
                    reservationId,
                    rs.getLong("amount"),
                    rs.getString("currency"),
                    rs.getString("payment_method_token"),
                    rs.getString("payment_method_token_digest"),
                    PaymentStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempt"),
                    rs.getString("failure_reason"));
        };
    }

    public Optional<PaymentRecord> findPaymentByReservationIdForUpdate(UUID reservationId) {
        return findPaymentByReservationId(reservationId, true);
    }

    public Optional<PaymentRecord> findPaymentByReservationId(UUID reservationId) {
        return findPaymentByReservationId(reservationId, false);
    }

    private Optional<PaymentRecord> findPaymentByReservationId(
            UUID reservationId,
            boolean forUpdate
    ) {
        try {
            String lockClause = forUpdate ? " for update" : "";
            return Optional.of(jdbc.queryForObject(
                    "select " + PAYMENT_COLUMNS
                            + " from payments where reservation_id = :reservationId"
                            + lockClause,
                    Map.of("reservationId", reservationId),
                    paymentRowMapper));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public PaymentRecord lockPayment(UUID reservationId) {
        return jdbc.queryForObject(
                "select " + PAYMENT_COLUMNS
                        + " from payments where reservation_id = :reservationId for update",
                Map.of("reservationId", reservationId),
                paymentRowMapper);
    }

    public LockedPayment lockOrCreatePayment(PaymentRecord candidate) {
        List<PaymentRecord> inserted = jdbc.query(
                """
                insert into payments (
                    id,
                    reservation_id,
                    amount,
                    currency,
                    payment_method_token,
                    payment_method_token_digest,
                    status,
                    attempt,
                    failure_reason
                )
                values (
                    :id,
                    :reservationId,
                    :amount,
                    :currency,
                    :paymentMethodToken,
                    :paymentMethodTokenDigest,
                    :status,
                    :attempt,
                    :failureReason
                )
                on conflict (reservation_id) do nothing
                returning id,
                          reservation_id,
                          amount,
                          currency,
                          payment_method_token,
                          payment_method_token_digest,
                          status,
                          attempt,
                          failure_reason
                """,
                paymentParams(candidate),
                paymentRowMapper);
        if (!inserted.isEmpty()) {
            return new LockedPayment(inserted.get(0), true);
        }

        PaymentRecord current = jdbc.queryForObject(
                "select " + PAYMENT_COLUMNS
                        + " from payments where reservation_id = :reservationId for update",
                Map.of("reservationId", candidate.reservationId()),
                paymentRowMapper);
        return new LockedPayment(current, false);
    }

    public PaymentRecord insertPayment(
            UUID paymentId,
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodToken,
            String paymentMethodTokenDigest,
            PaymentStatus status,
            String failureReason
    ) {
        PaymentRecord payment = new PaymentRecord(
                paymentId,
                reservationId,
                amount,
                currency,
                paymentMethodToken,
                paymentMethodTokenDigest,
                status,
                1,
                failureReason);
        jdbc.update(
                """
                insert into payments (
                    id,
                    reservation_id,
                    amount,
                    currency,
                    payment_method_token,
                    payment_method_token_digest,
                    status,
                    attempt,
                    failure_reason
                )
                values (
                    :id,
                    :reservationId,
                    :amount,
                    :currency,
                    :paymentMethodToken,
                    :paymentMethodTokenDigest,
                    :status,
                    :attempt,
                    :failureReason
                )
                """,
                paymentParams(payment));
        return payment;
    }

    public PaymentRecord completeProcessingPayment(
            UUID paymentId,
            int attempt,
            PaymentStatus status,
            String failureReason
    ) {
        jdbc.update(
                """
                update payments
                set status = :status,
                    payment_method_token = null,
                    failure_reason = :failureReason,
                    updated_at = now()
                where id = :paymentId
                  and status = 'PROCESSING'
                  and attempt = :attempt
                """,
                new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("attempt", attempt)
                        .addValue("status", status.name())
                        .addValue("failureReason", failureReason));
        return findPaymentById(paymentId);
    }

    public Optional<PaymentRecord> claimStaleProcessingPayment(
            UUID paymentId,
            Duration attemptTimeout
    ) {
        List<PaymentRecord> claimed = jdbc.query(
                """
                update payments
                set attempt = attempt + 1,
                    updated_at = now()
                where id = :paymentId
                  and status = 'PROCESSING'
                  and updated_at <= now() - (:timeoutMillis * interval '1 millisecond')
                returning id,
                          reservation_id,
                          amount,
                          currency,
                          payment_method_token,
                          payment_method_token_digest,
                          status,
                          attempt,
                          failure_reason
                """,
                new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("timeoutMillis", attemptTimeout.toMillis()),
                paymentRowMapper);
        return claimed.stream().findFirst();
    }

    public PaymentRecord attachProcessingPaymentToken(UUID paymentId, String paymentMethodToken) {
        return jdbc.queryForObject(
                """
                update payments
                set payment_method_token = :paymentMethodToken,
                    updated_at = now()
                where id = :paymentId
                  and status = 'PROCESSING'
                  and payment_method_token is null
                returning id,
                          reservation_id,
                          amount,
                          currency,
                          payment_method_token,
                          payment_method_token_digest,
                          status,
                          attempt,
                          failure_reason
                """,
                new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("paymentMethodToken", paymentMethodToken),
                paymentRowMapper);
    }

    public List<UUID> findStaleProcessingReservationIds(Duration attemptTimeout) {
        return jdbc.query(
                """
                select reservation_id
                from payments
                where status = 'PROCESSING'
                  and updated_at <= now() - (:timeoutMillis * interval '1 millisecond')
                order by updated_at, reservation_id
                limit 100
                """,
                Map.of("timeoutMillis", attemptTimeout.toMillis()),
                (rs, rowNum) -> rs.getObject("reservation_id", UUID.class));
    }

    public void markPaymentRefunded(UUID paymentId) {
        jdbc.update(
                """
                update payments
                set status = 'REFUNDED',
                    failure_reason = null,
                    updated_at = now()
                where id = :paymentId
                  and status = 'SUCCEEDED'
                """,
                Map.of("paymentId", paymentId));
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
                    "select " + REFUND_COLUMNS
                            + " from refunds where reservation_id = :reservationId"
                            + lockClause,
                    Map.of("reservationId", reservationId),
                    REFUND_ROW_MAPPER));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public RefundRecord insertRefund(
            UUID refundId,
            UUID reservationId,
            UUID paymentId
    ) {
        RefundRecord refund = new RefundRecord(
                refundId,
                reservationId,
                paymentId,
                RefundStatus.PROCESSING,
                1);
        jdbc.update(
                """
                insert into refunds (id, reservation_id, payment_id, status, attempt)
                values (:id, :reservationId, :paymentId, 'PROCESSING', 1)
                """,
                new MapSqlParameterSource()
                        .addValue("id", refundId)
                        .addValue("reservationId", reservationId)
                        .addValue("paymentId", paymentId));
        return refund;
    }

    public RefundRecord restartFailedRefund(UUID refundId) {
        return jdbc.queryForObject(
                """
                update refunds
                set status = 'PROCESSING',
                    attempt = attempt + 1,
                    updated_at = now()
                where id = :refundId
                  and status = 'FAILED'
                returning id, reservation_id, payment_id, status, attempt
                """,
                Map.of("refundId", refundId),
                REFUND_ROW_MAPPER);
    }

    public RefundRecord completeProcessingRefund(
            UUID refundId,
            int attempt,
            RefundStatus status
    ) {
        jdbc.update(
                """
                update refunds
                set status = :status,
                    updated_at = now()
                where id = :refundId
                  and status = 'PROCESSING'
                  and attempt = :attempt
                """,
                new MapSqlParameterSource()
                        .addValue("refundId", refundId)
                        .addValue("attempt", attempt)
                        .addValue("status", status.name()));
        return findRefundById(refundId);
    }

    public int failStaleProcessingRefunds(Duration timeout) {
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
                "select " + PAYMENT_COLUMNS + " from payments where id = :paymentId",
                Map.of("paymentId", paymentId),
                paymentRowMapper);
    }

    private RefundRecord findRefundById(UUID refundId) {
        return jdbc.queryForObject(
                "select " + REFUND_COLUMNS + " from refunds where id = :refundId",
                Map.of("refundId", refundId),
                REFUND_ROW_MAPPER);
    }

    private MapSqlParameterSource paymentParams(PaymentRecord payment) {
        return new MapSqlParameterSource()
                .addValue("id", payment.id())
                .addValue("reservationId", payment.reservationId())
                .addValue("amount", payment.amount())
                .addValue("currency", payment.currency())
                .addValue("paymentMethodToken", payment.paymentMethodToken())
                .addValue("paymentMethodTokenDigest", payment.paymentMethodTokenDigest())
                .addValue("status", payment.status().name())
                .addValue("attempt", payment.attempt())
                .addValue("failureReason", payment.failureReason());
    }

    public record LockedPayment(PaymentRecord payment, boolean created) {
    }
}
