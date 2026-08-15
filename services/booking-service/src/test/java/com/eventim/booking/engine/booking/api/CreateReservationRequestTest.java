package com.eventim.booking.engine.booking.api;

import static com.eventim.booking.engine.booking.api.CreateReservationRequest.MAX_EVENT_ID_LENGTH;
import static com.eventim.booking.engine.booking.api.CreateReservationRequest.MAX_SEAT_ID_LENGTH;
import static com.eventim.booking.engine.booking.api.CreateReservationRequest.MAX_SEATS_PER_RESERVATION;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

class CreateReservationRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheMaximumReservationRequestSize() {
        List<String> seatIds = IntStream.range(0, MAX_SEATS_PER_RESERVATION)
                .mapToObj(index -> index == 0
                        ? "s".repeat(MAX_SEAT_ID_LENGTH)
                        : "seat-" + index)
                .toList();
        CreateReservationRequest request = new CreateReservationRequest(
                "e".repeat(MAX_EVENT_ID_LENGTH),
                seatIds);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsTooManySeats() {
        CreateReservationRequest request = new CreateReservationRequest(
                "event-1",
                Collections.nCopies(MAX_SEATS_PER_RESERVATION + 1, "A-1"));

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .isEqualTo("seatIds"));
    }

    @Test
    void rejectsOversizedEventAndSeatIdentifiers() {
        CreateReservationRequest request = new CreateReservationRequest(
                "e".repeat(MAX_EVENT_ID_LENGTH + 1),
                List.of("s".repeat(MAX_SEAT_ID_LENGTH + 1)));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("eventId", "seatIds[0].<list element>");
    }
}
