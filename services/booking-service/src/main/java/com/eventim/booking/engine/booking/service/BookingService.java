package com.eventim.booking.engine.booking.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.booking.api.CreateReservationRequest;
import com.eventim.booking.engine.booking.api.ReservationResponse;
import com.eventim.booking.engine.booking.api.SeatAvailabilityResponse;
import com.eventim.booking.engine.booking.config.BookingProperties;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.repository.BookingRepository;
import com.eventim.booking.engine.booking.repository.SeatAvailabilityRow;

/**
 * Handles seat-availability queries and reservation creation. Each operation
 * is transactional so expired holds and seat ownership changes remain atomic;
 * checkout orchestration is kept in the checkout package.
 */
@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingProperties bookingProperties;

    public BookingService(
            BookingRepository bookingRepository,
            BookingProperties bookingProperties
    ) {
        this.bookingRepository = bookingRepository;
        this.bookingProperties = bookingProperties;
    }

    @Transactional
    public SeatAvailabilityResponse getSeats(String eventId) {
        if (bookingRepository.findEventCurrency(eventId).isEmpty()) {
            throw new NotFoundException("Event not found: " + eventId);
        }
        bookingRepository.releaseExpiredHoldsForEvent(eventId);

        List<SeatAvailabilityResponse.Seat> seats = new ArrayList<>();
        for (SeatAvailabilityRow row : bookingRepository.findSeats(eventId)) {
            seats.add(new SeatAvailabilityResponse.Seat(
                    row.seatLabel(),
                    row.status(),
                    row.expiresAt()));
        }

        return new SeatAvailabilityResponse(eventId, seats);
    }

    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request) {
        BookingRepository.ReservationInsertResult result = bookingRepository.createReservation(
                request.eventId(),
                request.seatIds(),
                bookingProperties.holdTtl());

        return new ReservationResponse(
                result.reservationId(),
                result.eventId(),
                result.seatIds(),
                ReservationStatus.HELD,
                result.expiresAt(),
                result.amount(),
                result.currency());
    }

}
