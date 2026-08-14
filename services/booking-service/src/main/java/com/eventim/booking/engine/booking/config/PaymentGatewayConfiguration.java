package com.eventim.booking.engine.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.eventim.booking.engine.booking.payment.PaymentGateway;
import com.eventim.booking.engine.booking.payment.http.HttpPaymentGateway;

/**
 * Builds the HTTP payment gateway with the configured payment-service endpoint
 * and connection timeouts.
 */
@Configuration
public class PaymentGatewayConfiguration {

    @Bean
    PaymentGateway paymentGateway(RestClient.Builder builder, BookingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.paymentConnectTimeout());
        requestFactory.setReadTimeout(properties.paymentReadTimeout());

        RestClient restClient = builder
                .baseUrl(properties.paymentBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new HttpPaymentGateway(restClient);
    }
}
