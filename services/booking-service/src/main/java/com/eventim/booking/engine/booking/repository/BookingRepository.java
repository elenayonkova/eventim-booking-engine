package com.eventim.booking.engine.booking.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.domain.SeatStatus;
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.NotFoundException;

@Repository
public class BookingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BookingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void releaseExpiredHolds() {
        List<UUID> expiredReservationIds = jdbc.query(
                """
                select id
                from reservations
                where status = 'HELD'
                  and expires_at <= now()
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (expiredReservationIds.isEmpty()) {
            return;
        }

        releaseReservations(expiredReservationIds);
    }

    public void releaseExpiredHoldsForEvent(String eventId) {
        List<UUID> expiredReservationIds = jdbc.query(
                """
                select id
                from reservations
                where event_id = :eventId
                  and status = 'HELD'
                  and expires_at <= now()
                """,
                Map.of("eventId", eventId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (expiredReservationIds.isEmpty()) {
            return;
        }

        releaseReservations(expiredReservationIds);
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
                (rs, rowNum) -> new SeatAvailabilityRow(
                        rs.getString("seat_label"),
                        SeatStatus.valueOf(rs.getString("status")),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getObject("hold_expires_at", OffsetDateTime.class)));
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
                (rs, rowNum) -> new SeatRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("seat_label"),
                        SeatStatus.valueOf(rs.getString("status"))));

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

        for (SeatRow seat : lockedSeats) {
            jdbc.update(
                    """
                    insert into reservation_seats (reservation_id, seat_id)
                    values (:reservationId, :seatId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("reservationId", reservationId)
                            .addValue("seatId", seat.id()));
        }

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

    private void releaseReservations(List<UUID> reservationIds) {
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

        jdbc.update(
                """
                update reservations
                set status = 'EXPIRED',
                    updated_at = now()
                where id in (:reservationIds)
                  and status = 'HELD'
                """,
                params);
    }

    private OffsetDateTime databaseNow() {
        return jdbc.getJdbcTemplate().queryForObject("select now()", OffsetDateTime.class);
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
