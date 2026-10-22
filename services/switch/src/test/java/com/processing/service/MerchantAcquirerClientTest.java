package com.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantAcquirerClientTest {
    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec post;
    @Mock
    private RestClient.RequestBodySpec body;
    @Mock
    private RestClient.ResponseSpec response;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        client = new MerchantAcquirerClient(SwitchTestFixtures.properties(), restClient);
    }

    @Test
    void shouldReturnFeeWhenAcquirerResponds() {
        // Arrange
        preparePost();
        var mapper = new ObjectMapper().findAndRegisterModules();
        when(response.body(ArgumentMatchers.<Class<Object>>any())).thenAnswer(invocation -> mapper.readValue(
                "{\"acquirerFee\":1.50}", invocation.<Class<?>>getArgument(0)));
        var request = SwitchTestFixtures.request();

        // Act
        var fee = client.fetchAcquiringFee(request.transmissionDateTime(), request.stan(), request.pan(),
                request.terminalId(), request.amount());

        // Assert
        assertThat(fee).isEqualByComparingTo("1.50");
    }

    @Test
    void shouldReturnNullWhenAcquirerBodyIsEmpty() {
        // Arrange
        preparePost();
        var request = SwitchTestFixtures.request();

        // Act
        var fee = client.fetchAcquiringFee(request.transmissionDateTime(), request.stan(), request.pan(),
                request.terminalId(), request.amount());

        // Assert
        assertThat(fee).isNull();
    }

    @Test
    void shouldReturnNullWhenAcquirerIsUnavailable() {
        // Arrange
        when(restClient.post()).thenThrow(new ResourceAccessException("timeout"));
        var request = SwitchTestFixtures.request();

        // Act
        var fee = client.fetchAcquiringFee(request.transmissionDateTime(), request.stan(), request.pan(),
                request.terminalId(), request.amount());

        // Assert
        assertThat(fee).isNull();
    }

    private void preparePost() {
        when(restClient.post()).thenReturn(post);
        when(post.uri("http://acquirer.test/api/simulator/merchant/fee")).thenReturn(body);
        when(body.body(any(Object.class))).thenReturn(body);
        when(body.retrieve()).thenReturn(response);
    }
}
