package com.eventim.booking.engine.booking.payment;

import java.util.UUID;
import java.util.Optional;

/**
 * Port used by checkout to charge and refund payments without coupling the
 * booking workflow to a particular transport or provider.
 */
public interface PaymentGateway {

    PaymentResult charge(ChargePayment command);

    Optional<PaymentResult> findPayment(UUID reservationId);

    RefundResult refund(UUID reservationId);
}
