package com.eventim.booking.engine.booking.payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentGateway {

    PaymentResult charge(ChargePayment command);

    Optional<PaymentResult> find(UUID reservationId);

    RefundResult refund(UUID reservationId);
}
