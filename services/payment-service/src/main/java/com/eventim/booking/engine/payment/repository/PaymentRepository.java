package com.eventim.booking.engine.payment.repository;

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

@Repository
public class PaymentRepository {

    private static final RowMapper<PaymentRecord> PAYMENT_ROW_MAPPER = (rs, rowNum) -> new PaymentRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("reservation_id", UUID.class),
            rs.getLong("amount"),
            rs.getString("currency"),
            rs.getString("payment_method_fingerprint"),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("failure_reason"));

    private static final RowMapper<RefundRecord> REFUND_ROW_MAPPER = (rs, rowNum) -> new RefundRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("reservation_id", UUID.class),
            rs.getObject("payment_id", UUID.class),
            RefundStatus.valueOf(rs.getString("status")));

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
        try {
            return Optional.of(jdbc.queryForObject(
                    """
                    select id, reservation_id, payment_id, status
                    from refunds
                    where reservation_id = :reservationId
                    """,
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

        return inserted.stream().findFirst();
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
        return insertPaymentIfAbsent(
                paymentId,
                reservationId,
                amount,
                currency,
                paymentMethodFingerprint,
                status,
                failureReason)
                .orElseThrow(() -> new IllegalStateException("Payment already exists for reservation " + reservationId));
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

        return inserted.stream().findFirst();
    }

    public void markPaymentRefunded(UUID paymentId) {
        updatePaymentStatus(paymentId, PaymentStatus.REFUNDED, null);
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
}
