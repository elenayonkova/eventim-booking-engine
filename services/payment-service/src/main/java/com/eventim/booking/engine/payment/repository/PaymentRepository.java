package com.eventim.booking.engine.payment.repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.eventim.booking.engine.payment.domain.PaymentStatus;
import com.eventim.booking.engine.payment.domain.RefundStatus;

@Repository
public class PaymentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                    (rs, rowNum) -> new PaymentRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("reservation_id", UUID.class),
                            rs.getLong("amount"),
                            rs.getString("currency"),
                            rs.getString("payment_method_fingerprint"),
                            PaymentStatus.valueOf(rs.getString("status")),
                            rs.getString("failure_reason"))));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<RefundRecord> findRefundByReservationIdForUpdate(UUID reservationId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    """
                    select id, reservation_id, payment_id, status
                    from refunds
                    where reservation_id = :reservationId
                    for update
                    """,
                    Map.of("reservationId", reservationId),
                    (rs, rowNum) -> new RefundRecord(
                            rs.getObject("id", UUID.class),
                            rs.getObject("reservation_id", UUID.class),
                            rs.getObject("payment_id", UUID.class),
                            RefundStatus.valueOf(rs.getString("status")))));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
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
                new MapSqlParameterSource()
                        .addValue("id", paymentId)
                        .addValue("reservationId", reservationId)
                        .addValue("amount", amount)
                        .addValue("currency", currency)
                        .addValue("paymentMethodFingerprint", paymentMethodFingerprint)
                        .addValue("status", status.name())
                        .addValue("failureReason", failureReason));

        return new PaymentRecord(paymentId, reservationId, amount, currency, paymentMethodFingerprint, status, failureReason);
    }

    public RefundRecord insertRefund(UUID refundId, UUID reservationId, UUID paymentId, RefundStatus status) {
        jdbc.update(
                """
                insert into refunds (id, reservation_id, payment_id, status)
                values (:id, :reservationId, :paymentId, :status)
                """,
                new MapSqlParameterSource()
                        .addValue("id", refundId)
                        .addValue("reservationId", reservationId)
                        .addValue("paymentId", paymentId)
                        .addValue("status", status.name()));

        return new RefundRecord(refundId, reservationId, paymentId, status);
    }

    public void markPaymentRefunded(UUID paymentId) {
        jdbc.update(
                """
                update payments
                set status = 'REFUNDED',
                    updated_at = now()
                where id = :paymentId
                """,
                Map.of("paymentId", paymentId));
    }
}
