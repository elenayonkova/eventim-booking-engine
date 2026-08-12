package com.eventim.booking.engine.booking.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.booking.api.CreateReservationRequest;
import com.eventim.booking.engine.booking.api.ReservationResponse;
import com.eventim.booking.engine.booking.api.SeatAvailabilityResponse;
import com.eventim.booking.engine.booking.config.BookingProperties;
import com.eventim.booking.engine.booking.domain.ReservationStatus;
import com.eventim.booking.engine.booking.repository.BookingRepository;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingProperties bookingProperties;

    public BookingService(BookingRepository bookingRepository, BookingProperties bookingProperties) {
        this.bookingRepository = bookingRepository;
        this.bookingProperties = bookingProperties;
    }

    @Transactional
    public SeatAvailabilityResponse getSeats(String eventId) {
        bookingRepository.releaseExpiredHoldsForEvent(eventId);

        if (!bookingRepository.eventExists(eventId)) {
            throw new NotFoundException("Event not found: " + eventId);
        }

        List<SeatAvailabilityResponse.Seat> seats = bookingRepository.findSeats(eventId).stream()
                .map(row -> new SeatAvailabilityResponse.Seat(
                        row.seatLabel(),
                        row.status(),
                        row.expiresAt()))
                .toList();

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
                result.expiresAt());
    }
}
