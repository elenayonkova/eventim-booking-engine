package com.eventim.booking.engine.booking.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.domain.SeatStatus;
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.NotFoundException;

@Repository
public class BookingRepository {

    private static final RowMapper<SeatAvailabilityRow> SEAT_AVAILABILITY_ROW_MAPPER = (rs, rowNum) ->
            new SeatAvailabilityRow(
                    rs.getString("seat_label"),
                    SeatStatus.valueOf(rs.getString("status")),
                    rs.getObject("reservation_id", UUID.class),
                    rs.getObject("hold_expires_at", OffsetDateTime.class));

    private static final RowMapper<SeatRow> SEAT_ROW_MAPPER = (rs, rowNum) -> new SeatRow(
            rs.getObject("id", UUID.class),
            rs.getString("seat_label"),
            SeatStatus.valueOf(rs.getString("status")));

    private static final RowMapper<ReservationRow> RESERVATION_ROW_MAPPER = (rs, rowNum) -> new ReservationRow(
            rs.getObject("id", UUID.class),
            rs.getString("event_id"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("payment_id", UUID.class),
            rs.getObject("checkout_amount", Long.class),
            rs.getString("checkout_currency"),
            rs.getString("payment_method_fingerprint"),
            rs.getObject("checkout_started_at", OffsetDateTime.class),
            rs.getString("payment_failure_reason"));

    private static final RowMapper<ReservationSeatRow> RESERVATION_SEAT_ROW_MAPPER = (rs, rowNum) ->
            new ReservationSeatRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("seat_label"),
                    SeatStatus.valueOf(rs.getString("status")),
                    rs.getObject("reservation_id", UUID.class),
                    rs.getLong("price_amount"),
                    rs.getString("currency"));

    private final NamedParameterJdbcTemplate jdbc;

    public BookingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void releaseExpiredHolds() {
        List<UUID> expiredReservationIds = jdbc.query(
                """
                with candidates as (
                    select id
                    from reservations
                    where status = 'HELD'
                      and expires_at <= now()
                    order by expires_at, id
                    for update skip locked
                    limit 500
                )
                update reservations reservation
                set status = 'EXPIRED',
                    updated_at = now()
                from candidates
                where reservation.id = candidates.id
                  and reservation.status = 'HELD'
                returning reservation.id
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (expiredReservationIds.isEmpty()) {
            return;
        }

        releaseHeldSeats(expiredReservationIds);
    }

    public void releaseExpiredHoldsForEvent(String eventId) {
        List<UUID> expiredReservationIds = jdbc.query(
                """
                with candidates as (
                    select id
                    from reservations
                    where event_id = :eventId
                      and status = 'HELD'
                      and expires_at <= now()
                    order by expires_at, id
                    for update
                )
                update reservations reservation
                set status = 'EXPIRED',
                    updated_at = now()
                from candidates
                where reservation.id = candidates.id
                  and reservation.status = 'HELD'
                returning reservation.id
                """,
                Map.of("eventId", eventId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (expiredReservationIds.isEmpty()) {
            return;
        }

        releaseHeldSeats(expiredReservationIds);
    }

    public boolean eventExists(String eventId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from events where id = :eventId",
                Map.of("eventId", eventId),
                Integer.class);
        return count != null && count > 0;
    }

    public List<SeatAvailabilityRow> findSeats(String eventId) {
        return jdbc.query(
                """
                select seat_label, status, reservation_id, hold_expires_at
                from seats
                where event_id = :eventId
                order by seat_label
                """,
                Map.of("eventId", eventId),
                SEAT_AVAILABILITY_ROW_MAPPER);
    }

    public ReservationInsertResult createReservation(String eventId, List<String> requestedSeatLabels, Duration holdTtl) {
        if (!eventExists(eventId)) {
            throw new NotFoundException("Event not found: " + eventId);
        }

        List<String> sortedSeatLabels = normalizeSeatLabels(requestedSeatLabels);
        releaseExpiredHoldsForEvent(eventId);

        MapSqlParameterSource selectParams = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("seatLabels", sortedSeatLabels);

        List<SeatRow> lockedSeats = jdbc.query(
                """
                select id, seat_label, status
                from seats
                where event_id = :eventId
                  and seat_label in (:seatLabels)
                order by seat_label
                for update
                """,
                selectParams,
                SEAT_ROW_MAPPER);

        if (lockedSeats.size() != sortedSeatLabels.size()) {
            Set<String> foundLabels = lockedSeats.stream()
                    .map(SeatRow::seatLabel)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> missingLabels = sortedSeatLabels.stream()
                    .filter(label -> !foundLabels.contains(label))
                    .toList();
            throw new NotFoundException("Seats not found for event " + eventId + ": " + missingLabels);
        }

        List<String> unavailableLabels = lockedSeats.stream()
                .filter(seat -> seat.status() != SeatStatus.AVAILABLE)
                .map(SeatRow::seatLabel)
                .toList();

        if (!unavailableLabels.isEmpty()) {
            throw new ConflictException("Seats are not available: " + unavailableLabels);
        }

        UUID reservationId = UUID.randomUUID();
        OffsetDateTime expiresAt = databaseNow().plus(holdTtl);

        jdbc.update(
                """
                insert into reservations (id, event_id, status, expires_at)
                values (:id, :eventId, :status, :expiresAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", reservationId)
                        .addValue("eventId", eventId)
                        .addValue("status", ReservationStatus.HELD.name())
                        .addValue("expiresAt", expiresAt));

        SqlParameterSource[] reservationSeatParams = lockedSeats.stream()
                .map(seat -> new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("seatId", seat.id()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(
                """
                insert into reservation_seats (reservation_id, seat_id)
                values (:reservationId, :seatId)
                """,
                reservationSeatParams);

        List<UUID> seatIds = lockedSeats.stream()
                .map(SeatRow::id)
                .toList();

        jdbc.update(
                """
                update seats
                set status = 'HELD',
                    reservation_id = :reservationId,
                    hold_expires_at = :expiresAt,
                    version = version + 1
                where id in (:seatIds)
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("expiresAt", expiresAt)
                        .addValue("seatIds", seatIds));

        return new ReservationInsertResult(reservationId, eventId, sortedSeatLabels, expiresAt);
    }

    public ReservationRow lockReservation(UUID reservationId) {
        try {
            return jdbc.queryForObject(
                    """
                    select id,
                           event_id,
                           status,
                           expires_at,
                           payment_id,
                           checkout_amount,
                           checkout_currency,
                           payment_method_fingerprint,
                           checkout_started_at,
                           payment_failure_reason
                    from reservations
                    where id = :reservationId
                    for update
                    """,
                    Map.of("reservationId", reservationId),
                    RESERVATION_ROW_MAPPER);
        } catch (EmptyResultDataAccessException exception) {
            throw new NotFoundException("Reservation not found: " + reservationId);
        }
    }

    public List<ReservationSeatRow> lockReservationSeats(UUID reservationId) {
        return jdbc.query(
                """
                select seat.id,
                       seat.seat_label,
                       seat.status,
                       seat.reservation_id,
                       seat.price_amount,
                       seat.currency
                from reservation_seats reservation_seat
                join seats seat on seat.id = reservation_seat.seat_id
                where reservation_seat.reservation_id = :reservationId
                order by seat.id
                for update of seat
                """,
                Map.of("reservationId", reservationId),
                RESERVATION_SEAT_ROW_MAPPER);
    }

    public void markPaymentPending(
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodFingerprint
    ) {
        int updated = jdbc.update(
                """
                update reservations
                set status = 'PAYMENT_PENDING',
                    checkout_amount = :amount,
                    checkout_currency = :currency,
                    payment_method_fingerprint = :paymentMethodFingerprint,
                    checkout_started_at = now(),
                    payment_failure_reason = null,
                    updated_at = now()
                where id = :reservationId
                  and status = 'HELD'
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("amount", amount)
                        .addValue("currency", currency)
                        .addValue("paymentMethodFingerprint", paymentMethodFingerprint));
        requireSingleUpdate(updated, "Reservation could not enter payment processing: " + reservationId);
    }

    public void markBooked(UUID reservationId, UUID paymentId) {
        int updated = jdbc.update(
                """
                update reservations
                set status = 'BOOKED',
                    payment_id = :paymentId,
                    payment_failure_reason = null,
                    updated_at = now()
                where id = :reservationId
                  and status = 'PAYMENT_PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("paymentId", paymentId));
        requireSingleUpdate(updated, "Reservation could not be booked: " + reservationId);
    }

    public void recordProcessingPayment(UUID reservationId, UUID paymentId) {
        int updated = jdbc.update(
                """
                update reservations
                set payment_id = :paymentId,
                    updated_at = now()
                where id = :reservationId
                  and status = 'PAYMENT_PENDING'
                  and (payment_id is null or payment_id = :paymentId)
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("paymentId", paymentId));
        requireSingleUpdate(updated, "Reservation has a different processing payment: " + reservationId);
    }

    public List<UUID> findPaymentPendingReservationIds() {
        return jdbc.query(
                """
                select id
                from reservations
                where status = 'PAYMENT_PENDING'
                order by checkout_started_at, id
                limit 100
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public List<UUID> findRefundRequiredReservationIds() {
        return jdbc.query(
                """
                select id
                from reservations
                where status = 'REFUND_REQUIRED'
                order by updated_at, id
                limit 100
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public void bookSeats(UUID reservationId) {
        jdbc.update(
                """
                update seats
                set status = 'BOOKED',
                    hold_expires_at = null,
                    version = version + 1
                where reservation_id = :reservationId
                  and status = 'HELD'
                """,
                Map.of("reservationId", reservationId));
    }

    public void markPaymentFailed(UUID reservationId, UUID paymentId, String failureReason) {
        int updated = jdbc.update(
                """
                update reservations
                set status = 'PAYMENT_FAILED',
                    payment_id = :paymentId,
                    payment_failure_reason = :failureReason,
                    updated_at = now()
                where id = :reservationId
                  and status = 'PAYMENT_PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("paymentId", paymentId)
                        .addValue("failureReason", failureReason));
        requireSingleUpdate(updated, "Reservation could not be marked payment failed: " + reservationId);
    }

    public void markRefundRequired(UUID reservationId, UUID paymentId, String reason) {
        int updated = jdbc.update(
                """
                update reservations
                set status = 'REFUND_REQUIRED',
                    payment_id = :paymentId,
                    payment_failure_reason = :reason,
                    updated_at = now()
                where id = :reservationId
                  and status = 'PAYMENT_PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("paymentId", paymentId)
                        .addValue("reason", reason));
        requireSingleUpdate(updated, "Reservation could not enter refund recovery: " + reservationId);
    }

    public void markRefunded(UUID reservationId) {
        int updated = jdbc.update(
                """
                update reservations
                set status = 'REFUNDED',
                    updated_at = now()
                where id = :reservationId
                  and status = 'REFUND_REQUIRED'
                """,
                Map.of("reservationId", reservationId));
        requireSingleUpdate(updated, "Reservation could not be marked refunded: " + reservationId);
    }

    public void expireHeldReservation(UUID reservationId) {
        int updated = jdbc.update(
                """
                update reservations
                set status = 'EXPIRED',
                    updated_at = now()
                where id = :reservationId
                  and status = 'HELD'
                """,
                Map.of("reservationId", reservationId));
        requireSingleUpdate(updated, "Reservation could not be expired: " + reservationId);
        releaseHeldSeats(List.of(reservationId));
    }

    public void releaseHeldSeats(UUID reservationId) {
        releaseHeldSeats(List.of(reservationId));
    }

    public OffsetDateTime databaseNow() {
        return jdbc.getJdbcTemplate().queryForObject("select now()", OffsetDateTime.class);
    }

    private void releaseHeldSeats(List<UUID> reservationIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("reservationIds", reservationIds);

        jdbc.update(
                """
                update seats
                set status = 'AVAILABLE',
                    reservation_id = null,
                    hold_expires_at = null,
                    version = version + 1
                where reservation_id in (:reservationIds)
                  and status = 'HELD'
                """,
                params);
    }

    private void requireSingleUpdate(int updated, String message) {
        if (updated != 1) {
            throw new ConflictException(message);
        }
    }

    private List<String> normalizeSeatLabels(List<String> requestedSeatLabels) {
        List<String> normalizedLabels = requestedSeatLabels.stream()
                .map(String::trim)
                .filter(label -> !label.isBlank())
                .sorted()
                .toList();

        if (normalizedLabels.size() != requestedSeatLabels.size()) {
            throw new ConflictException("Seat IDs must be non-blank");
        }

        if (normalizedLabels.stream().distinct().count() != normalizedLabels.size()) {
            throw new ConflictException("Seat IDs must be unique");
        }

        return normalizedLabels;
    }

    public record ReservationInsertResult(
            UUID reservationId,
            String eventId,
            List<String> seatIds,
            OffsetDateTime expiresAt
    ) {
    }
}
