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
            "id, reservation_id, amount, currency, payment_method_fingerprint, status, failure_reason";
    private static final String REFUND_COLUMNS =
            "id, reservation_id, payment_id, status, attempt";

    private static final RowMapper<PaymentRecord> PAYMENT_ROW_MAPPER = new RowMapper<PaymentRecord>() {
        @Override
        public PaymentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PaymentRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("reservation_id", UUID.class),
                    rs.getObject("amount", Long.class),
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
                    RefundStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempt"));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PaymentRecord> findPaymentByReservationId(UUID reservationId) {
        return findPaymentByReservationId(reservationId, false);
    }

    public Optional<PaymentRecord> findPaymentByReservationIdForUpdate(UUID reservationId) {
        return findPaymentByReservationId(reservationId, true);
    }

    public PaymentRecord lockPayment(UUID reservationId) {
        return jdbc.queryForObject(
                "select " + PAYMENT_COLUMNS
                        + " from payments where reservation_id = :reservationId for update",
                Map.of("reservationId", reservationId),
                PAYMENT_ROW_MAPPER);
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
                    PAYMENT_ROW_MAPPER));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public LockedPayment lockOrCreatePayment(PaymentRecord candidate) {
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
                returning id,
                          reservation_id,
                          amount,
                          currency,
                          payment_method_fingerprint,
                          status,
                          failure_reason
                """,
                paymentParams(candidate),
                PAYMENT_ROW_MAPPER);
        if (!inserted.isEmpty()) {
            return new LockedPayment(inserted.get(0), true);
        }

        PaymentRecord current = jdbc.queryForObject(
                "select " + PAYMENT_COLUMNS
                        + " from payments where reservation_id = :reservationId for update",
                Map.of("reservationId", candidate.reservationId()),
                PAYMENT_ROW_MAPPER);
        return new LockedPayment(current, false);
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
        PaymentRecord payment = new PaymentRecord(
                paymentId,
                reservationId,
                amount,
                currency,
                paymentMethodFingerprint,
                status,
                failureReason);
        jdbc.update(
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
                """,
                paymentParams(payment));
        return payment;
    }

    public PaymentRecord markCancellationPending(UUID paymentId) {
        return transitionPayment(
                paymentId,
                PaymentStatus.PROCESSING,
                PaymentStatus.CANCELLATION_PENDING,
                null);
    }

    public PaymentRecord completeProcessingPayment(
            UUID paymentId,
            PaymentStatus status,
            String failureReason
    ) {
        return transitionPayment(
                paymentId,
                PaymentStatus.PROCESSING,
                status,
                failureReason);
    }

    public PaymentRecord completePendingCancellation(
            UUID paymentId,
            PaymentStatus status,
            String failureReason
    ) {
        return transitionPayment(
                paymentId,
                PaymentStatus.CANCELLATION_PENDING,
                status,
                failureReason);
    }

    private PaymentRecord transitionPayment(
            UUID paymentId,
            PaymentStatus expectedStatus,
            PaymentStatus newStatus,
            String failureReason
    ) {
        jdbc.update(
                """
                update payments
                set status = :newStatus,
                    failure_reason = :failureReason,
                    updated_at = now()
                where id = :paymentId
                  and status = :expectedStatus
                """,
                new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("expectedStatus", expectedStatus.name())
                        .addValue("newStatus", newStatus.name())
                        .addValue("failureReason", failureReason));
        return findPaymentById(paymentId);
    }

    public void touchPayment(UUID paymentId) {
        jdbc.update(
                "update payments set updated_at = now() where id = :paymentId",
                Map.of("paymentId", paymentId));
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

    public int markStalePaymentsUnknown(Duration timeout) {
        return jdbc.update(
                """
                update payments
                set status = 'UNKNOWN',
                    failure_reason = 'Payment provider outcome is unknown; reconciliation required',
                    updated_at = now()
                where status in ('PROCESSING', 'CANCELLATION_PENDING')
                  and updated_at <= now() - (:timeoutSeconds * interval '1 second')
                """,
                Map.of("timeoutSeconds", timeout.toSeconds()));
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
                PAYMENT_ROW_MAPPER);
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
                .addValue("paymentMethodFingerprint", payment.paymentMethodFingerprint())
                .addValue("status", payment.status().name())
                .addValue("failureReason", payment.failureReason());
    }

    public record LockedPayment(PaymentRecord payment, boolean created) {
    }
}
