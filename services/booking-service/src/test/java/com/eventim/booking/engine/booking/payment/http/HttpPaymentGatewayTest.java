package com.eventim.booking.engine.booking.payment.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.eventim.booking.engine.booking.payment.ChargePayment;
import com.eventim.booking.engine.booking.payment.PaymentSimulation;
import com.eventim.booking.engine.booking.payment.PaymentStatus;
import com.eventim.booking.engine.booking.payment.RefundStatus;

class HttpPaymentGatewayTest {

    MockRestServiceServer server;
    HttpPaymentGateway paymentGateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment.test");
        server = MockRestServiceServer.bindTo(builder).build();
        paymentGateway = new HttpPaymentGateway(builder.build());
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void chargeSendsPaymentPayloadAndSimulationHeaders() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://payment.test/v1/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Simulate-Delay-Ms", "250"))
                .andExpect(header("X-Simulate-Failure", "true"))
                .andExpect(content().json("""
                        {
                          "reservationId": "%s",
                          "amount": 10000,
                          "currency": "EUR",
                          "paymentMethodToken": "pm-test"
                        }
                        """.formatted(reservationId)))
                .andRespond(withSuccess("""
                        {
                          "paymentId": "%s",
                          "reservationId": "%s",
                          "amount": 10000,
                          "currency": "EUR",
                          "paymentMethodTokenDigest": "token-digest",
                          "status": "SUCCEEDED",
                          "failureReason": null
                        }
                        """.formatted(paymentId, reservationId), MediaType.APPLICATION_JSON));

        var result = paymentGateway.charge(new ChargePayment(
                reservationId,
                10_000,
                "EUR",
                "pm-test",
                new PaymentSimulation(250L, "true")));

        assertThat(result.paymentId()).isEqualTo(paymentId);
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void refundResponseMapsAllIdentifiers() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        server.expect(requestTo("http://payment.test/v1/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"reservationId": "%s"}
                        """.formatted(reservationId)))
                .andRespond(withSuccess("""
                        {
                          "refundId": "%s",
                          "reservationId": "%s",
                          "paymentId": "%s",
                          "status": "SUCCEEDED"
                        }
                        """.formatted(refundId, reservationId, paymentId),
                        MediaType.APPLICATION_JSON));

        var result = paymentGateway.refund(reservationId);

        assertThat(result.refundId()).isEqualTo(refundId);
        assertThat(result.reservationId()).isEqualTo(reservationId);
        assertThat(result.paymentId()).isEqualTo(paymentId);
        assertThat(result.status()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void paymentLookupReturnsTheStoredPayment() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://payment.test/v1/payments/" + reservationId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "paymentId": "%s",
                          "reservationId": "%s",
                          "amount": 10000,
                          "currency": "EUR",
                          "paymentMethodTokenDigest": "token-digest",
                          "status": "SUCCEEDED",
                          "failureReason": null
                        }
                        """.formatted(paymentId, reservationId), MediaType.APPLICATION_JSON));

        var result = paymentGateway.findPayment(reservationId);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().paymentId()).isEqualTo(paymentId);
    }

    @Test
    void missingPaymentLookupReturnsEmpty() {
        UUID reservationId = UUID.randomUUID();
        server.expect(requestTo("http://payment.test/v1/payments/" + reservationId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(paymentGateway.findPayment(reservationId)).isEmpty();
    }
}
