package com.eventim.booking.engine.booking.api;

import static com.eventim.booking.engine.booking.payment.PaymentSimulation.MAX_DELAY_MS;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.eventim.booking.engine.booking.service.BookingService;
import com.eventim.booking.engine.booking.service.checkout.CheckoutService;

@RestController
@RequestMapping("/v1")
public class BookingController {

    private final BookingService bookingService;
    private final CheckoutService checkoutService;

    public BookingController(BookingService bookingService, CheckoutService checkoutService) {
        this.bookingService = bookingService;
        this.checkoutService = checkoutService;
    }

    @GetMapping("/events/{eventId}/seats")
    public SeatAvailabilityResponse getSeats(@PathVariable String eventId) {
        return bookingService.getSeats(eventId);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse createReservation(@Valid @RequestBody CreateReservationRequest request) {
        return bookingService.createReservation(request);
    }

    @PostMapping("/checkout")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request,
            @RequestHeader(name = "X-Simulate-Delay-Ms", required = false)
            @Min(0) @Max(MAX_DELAY_MS) Long simulatedDelayMs,
            @RequestHeader(name = "X-Simulate-Failure", required = false) String simulatedFailure) {
        return checkoutService.checkout(request, simulatedDelayMs, simulatedFailure);
    }
}
