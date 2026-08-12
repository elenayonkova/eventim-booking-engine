package com.eventim.booking.engine.booking.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eventim.booking.engine.booking.repository.BookingRepository;

@Component
public class ReservationExpiryJob {

    private final BookingRepository bookingRepository;

    public ReservationExpiryJob(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Scheduled(fixedDelayString = "${booking.expiry-sweep-ms:30000}")
    @Transactional
    public void releaseExpiredHolds() {
        bookingRepository.releaseExpiredHolds();
    }
}
