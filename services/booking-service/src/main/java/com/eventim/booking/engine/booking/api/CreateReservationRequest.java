package com.eventim.booking.engine.booking.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateReservationRequest(
        @NotBlank @Size(max = MAX_EVENT_ID_LENGTH) String eventId,
        @NotEmpty @Size(max = MAX_SEATS_PER_RESERVATION)
        List<@NotBlank @Size(max = MAX_SEAT_ID_LENGTH) String> seatIds
) {

    public static final int MAX_EVENT_ID_LENGTH = 100;
    public static final int MAX_SEAT_ID_LENGTH = 100;
    public static final int MAX_SEATS_PER_RESERVATION = 20;
}
