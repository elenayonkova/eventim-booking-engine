package com.eventim.booking.engine.booking.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateReservationRequest(
        @NotBlank String eventId,
        @NotEmpty List<@NotBlank String> seatIds
) {
}
