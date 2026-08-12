package com.eventim.booking.engine.booking.payment.http;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.eventim.booking.engine.booking.payment.ChargePayment;
import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentSimulation;
import com.eventim.booking.engine.booking.payment.PaymentStatus;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.payment.RefundStatus;
import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient restClient;

    public HttpPaymentGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PaymentResult charge(ChargePayment command) {
        try {
            PaymentResponse response = restClient.post()
                    .uri("/v1/payments")
                    .headers(headers -> addSimulationHeaders(headers, command.simulation()))
                    .body(new PaymentRequest(
                            command.reservationId(),
                            command.amount(),
                            command.currency(),
                            command.paymentMethodToken()))
                    .retrieve()
                    .body(PaymentResponse.class);
            if (response == null) {
                throw new ExternalServiceException("Payment service returned an empty response");
            }
            return response.toResult();
        } catch (HttpClientErrorException.Conflict exception) {
            throw new ConflictException("Payment service rejected the idempotency payload");
        } catch (RestClientException exception) {
            throw new ExternalServiceException("Payment outcome is unknown; retry checkout safely", exception);
        }
    }

    @Override
    public Optional<PaymentResult> find(UUID reservationId) {
        try {
            PaymentResponse response = restClient.get()
                    .uri("/v1/payments/by-reservation/{reservationId}", reservationId)
                    .retrieve()
                    .body(PaymentResponse.class);
            return Optional.ofNullable(response).map(PaymentResponse::toResult);
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new ExternalServiceException("Payment status could not be retrieved", exception);
        }
    }

    @Override
    public RefundResult refund(UUID reservationId) {
        try {
            RefundResponse response = restClient.post()
                    .uri("/v1/refunds")
                    .body(new RefundRequest(reservationId))
                    .retrieve()
                    .body(RefundResponse.class);
            if (response == null) {
                throw new ExternalServiceException("Payment service returned an empty refund response");
            }
            return response.toResult();
        } catch (RestClientException exception) {
            throw new ExternalServiceException("Refund could not be confirmed; retry checkout safely", exception);
        }
    }

    private void addSimulationHeaders(HttpHeaders headers, PaymentSimulation simulation) {
        if (simulation.delayMs() != null) {
            headers.set("X-Simulate-Delay-Ms", simulation.delayMs().toString());
        }
        if (simulation.failure() != null && !simulation.failure().isBlank()) {
            headers.set("X-Simulate-Failure", simulation.failure());
        }
    }

    private record PaymentRequest(
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodToken
    ) {
    }

    private record PaymentResponse(
            UUID paymentId,
            UUID reservationId,
            long amount,
            String currency,
            String paymentMethodFingerprint,
            PaymentStatus status,
            String failureReason
    ) {
        PaymentResult toResult() {
            return new PaymentResult(
                    paymentId,
                    reservationId,
                    amount,
                    currency,
                    paymentMethodFingerprint,
                    status,
                    failureReason);
        }
    }

    private record RefundRequest(UUID reservationId) {
    }

    private record RefundResponse(
            UUID refundId,
            UUID reservationId,
            UUID paymentId,
            RefundStatus status
    ) {
        RefundResult toResult() {
            return new RefundResult(refundId, reservationId, paymentId, status);
        }
    }
}
