package com.eventim.booking.engine.booking.payment;

import java.util.Optional;
import java.util.UUID;

/**
 * Port used by checkout to charge, inspect, cancel, and refund payments without
 * coupling the booking workflow to a particular transport or provider.
 */
public interface PaymentGateway {

    PaymentResult charge(ChargePayment command);

    Optional<PaymentResult> find(UUID reservationId);

    PaymentCancellationResult cancel(UUID reservationId);

    RefundResult refund(UUID reservationId);
}
