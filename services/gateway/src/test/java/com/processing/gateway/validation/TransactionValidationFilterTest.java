package com.processing.gateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionValidationFilterTest {

    private static final String VALID_BODY = """
            {"mti":"0100","stan":"000001","pan":"4000001234567899","processingCode":"000000",
             "amount":100,"currencyCode":"643","transmissionDateTime":"2026-09-24T12:00:00Z",
             "terminalId":"TERM0001","merchantId":"MERCH0000000001","mcc":"5411","acquirerId":"ACQ001"}
            """;

    @Mock
    private GatewayFilterChain chain;
    @Mock
    private GatewayMetrics metrics;

    private TransactionValidationFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        filter = new TransactionValidationFilter(objectMapper, new TransactionRequestValidator(), metrics);
    }

    @DisplayName("A-19: сумма 0 не передаётся в Switch")
    @Test
    void shouldNotForwardWhenAmountIsZero() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions")
                .body(VALID_BODY.replace("\"amount\":100", "\"amount\":0")));

        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Assert
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(metrics).recordValidationRejected("invalid_request");
        verifyNoInteractions(chain);
    }

    @Test
    void shouldForwardUnchangedBodyWhenRequestIsValid() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").body(VALID_BODY));
        when(chain.filter(any())).thenReturn(Mono.empty());

        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Assert
        var captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        var forwarded = captor.getValue().getRequest();
        assertThat(forwarded.getHeaders().getContentLength()).isEqualTo(VALID_BODY.getBytes(StandardCharsets.UTF_8).length);
        StepVerifier.create(DataBufferUtils.join(forwarded.getBody()).map(buffer -> {
            var body = buffer.toString(StandardCharsets.UTF_8);
            DataBufferUtils.release(buffer);
            return body;
        })).expectNext(VALID_BODY).verifyComplete();
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{invalid", "null"})
    void shouldReturnBadRequestWhenBodyIsInvalid(String body) throws Exception {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").body(body));

        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Assert
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        var error = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(error.path("error").asText()).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(chain);
        verify(metrics).recordValidationRejected(body.equals("null") ? "invalid_request" : "invalid_json");
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/transactions", "POST, /api/cards"})
    void shouldSkipValidationWhenRequestIsNotATransaction(String method, String path) {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Assert
        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

}
