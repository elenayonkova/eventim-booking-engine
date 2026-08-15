package com.eventim.booking.engine.booking.payment.http;

import java.util.UUID;
import java.util.Optional;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;

import com.eventim.booking.engine.booking.payment.ChargePayment;
import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.PaymentResult;
import com.eventim.booking.engine.booking.payment.PaymentSimulation;
import com.eventim.booking.engine.booking.payment.PaymentStatus;
import com.eventim.booking.engine.booking.payment.RefundResult;
import com.eventim.booking.engine.booking.payment.RefundStatus;
import com.eventim.booking.engine.booking.service.ExternalServiceException;

/**
 * HTTP adapter for {@link PaymentGateway}. It translates payment-service
 * payloads into booking payment results and maps transport failures to service
 * exceptions understood by the checkout workflow.
 */
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient restClient;

    public HttpPaymentGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<PaymentResult> findPayment(UUID reservationId) {
        try {
            PaymentResponse response = restClient.get()
                    .uri("/v1/payments/{reservationId}", reservationId)
                    .retrieve()
                    .body(PaymentResponse.class);
            if (response == null) {
                throw new ExternalServiceException("Payment service returned an empty response");
            }
            return Optional.of(response.toResult());
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new ExternalServiceException("Payment status could not be determined", exception);
        }
    }

    @Override
    public PaymentResult charge(ChargePayment command) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/v1/payments");
            addSimulationHeaders(request, command.simulation());

            PaymentResponse response = request
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
        } catch (RestClientException exception) {
            throw new ExternalServiceException("Payment outcome is unknown; retry checkout safely", exception);
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

    private void addSimulationHeaders(RestClient.RequestBodySpec request, PaymentSimulation simulation) {
        if (simulation.delayMs() != null) {
            request.header("X-Simulate-Delay-Ms", simulation.delayMs().toString());
        }
        if (simulation.failure() != null && !simulation.failure().isBlank()) {
            request.header("X-Simulate-Failure", simulation.failure());
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
            String paymentMethodTokenDigest,
            PaymentStatus status,
            String failureReason
    ) {
        PaymentResult toResult() {
            return new PaymentResult(
                    paymentId,
                    reservationId,
                    amount,
                    currency,
                    paymentMethodTokenDigest,
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
