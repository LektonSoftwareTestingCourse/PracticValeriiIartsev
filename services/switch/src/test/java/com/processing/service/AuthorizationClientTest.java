package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {
    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec post;
    @Mock
    private RestClient.RequestBodySpec body;
    @Mock
    private RestClient.ResponseSpec response;
    @Mock
    private RestClient.RequestHeadersUriSpec<?> get;
    @Mock
    private RestClient.RequestHeadersSpec<?> headers;

    private AuthorizationClient client;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        var retry = Retry.of("authorization-unit", RetryConfig.custom().maxAttempts(3)
                .waitDuration(Duration.ZERO).build());
        breaker = CircuitBreaker.ofDefaults("authorization-unit");
        client = new AuthorizationClient(SwitchTestFixtures.properties(), restClient, retry, breaker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnBusinessResponseWhenAuthorizationHasValidStatus(String status) {
        // Arrange
        preparePost("/api/internal/authorize");
        var expected = SwitchTestFixtures.response(status);
        when(response.body(AuthorizationResponse.class)).thenReturn(expected);

        // Act
        var result = client.authorize(SwitchTestFixtures.request());

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidAuthorizationResponses")
    void shouldFailWhenAuthorizationReturnsNullOrUnsupportedStatus(AuthorizationResponse invalid) {
        // Arrange
        preparePost("/api/internal/authorize");
        when(response.body(AuthorizationResponse.class)).thenReturn(invalid);

        // Act
        var error = catchThrowable(() -> client.authorize(SwitchTestFixtures.request()));

        // Assert
        assertThat(error).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization").hasMessageContaining("after 3 attempts");
    }

    @Test
    void shouldReturnResponseWhenTransientFailureIsFollowedBySuccess() {
        // Arrange
        preparePost("/api/internal/authorize");
        var expected = SwitchTestFixtures.response("APPROVED");
        when(response.body(AuthorizationResponse.class)).thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(expected);

        // Act
        var result = client.authorize(SwitchTestFixtures.request());

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("requestFailures")
    void shouldReportLastErrorWhenAllAttemptsFail(RuntimeException failure, String lastError) {
        // Arrange
        preparePost("/api/internal/authorize");
        when(response.body(AuthorizationResponse.class)).thenThrow(failure);

        // Act
        var error = catchThrowable(() -> client.authorize(SwitchTestFixtures.request()));

        // Assert
        assertThat(error).isInstanceOf(AuthorizationException.class).hasMessageContaining(lastError)
                .hasMessageContaining("after 3 attempts");
        assertThat(((AuthorizationException) error).getStan()).isEqualTo("000001");
    }

    @Test
    void shouldDeclineImmediatelyWhenCircuitBreakerIsOpen() {
        // Arrange
        breaker.transitionToOpenState();

        // Act
        var error = catchThrowable(() -> client.authorize(SwitchTestFixtures.request()));

        // Assert
        assertThat(error).isInstanceOf(AuthorizationException.class).hasMessageContaining("Circuit breaker open");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnBusinessResponseWhenRollbackHasValidStatus(String status) {
        // Arrange
        preparePost("/api/internal/rollback");
        var expected = new RollbackResponse("123456789012", "00", status, null, 12);
        when(response.body(RollbackResponse.class)).thenReturn(expected);
        var original = SwitchTestFixtures.request();

        // Act
        var result = client.rollback(original, expected.rrn());

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidRollbackResponses")
    void shouldReturnNullWhenRollbackResponseIsInvalid(RollbackResponse invalid) {
        // Arrange
        preparePost("/api/internal/rollback");
        when(response.body(RollbackResponse.class)).thenReturn(invalid);

        // Act
        var result = client.rollback(SwitchTestFixtures.request(), "123456789012");

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackServiceIsUnavailable() {
        // Arrange
        when(restClient.post()).thenThrow(new ResourceAccessException("timeout"));

        // Act
        var result = client.rollback(SwitchTestFixtures.request(), "123456789012");

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnOkWhenHealthRequestSucceeds() {
        // Arrange
        doReturn(get).when(restClient).get();
        doReturn(headers).when(get).uri("http://authorization.test/health");
        when(headers.retrieve()).thenReturn(response);

        // Act
        var result = client.checkHealth();

        // Assert
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void shouldReturnDownWhenHealthRequestFails() {
        // Arrange
        when(restClient.get()).thenThrow(new ResourceAccessException("timeout"));

        // Act
        var result = client.checkHealth();

        // Assert
        assertThat(result).isEqualTo("down");
    }

    static Stream<AuthorizationResponse> invalidAuthorizationResponses() {
        return Stream.of(null, SwitchTestFixtures.response("PENDING"), SwitchTestFixtures.response(null));
    }

    static Stream<RollbackResponse> invalidRollbackResponses() {
        return Stream.of(null, new RollbackResponse("123456789012", "00", "PENDING", null, 0),
                new RollbackResponse("123456789012", "00", null, null, 0));
    }

    static Stream<Arguments> requestFailures() {
        return Stream.of(arguments(new ResourceAccessException("timeout"), "timeout"),
                arguments(new IllegalStateException("wrapper", new RuntimeException("connection lost")), "connection lost"),
                arguments(new IllegalStateException((String) null), "unknown"));
    }

    private void preparePost(String path) {
        when(restClient.post()).thenReturn(post);
        when(post.uri("http://authorization.test" + path)).thenReturn(body);
        when(body.body(any(Object.class))).thenReturn(body);
        when(body.retrieve()).thenReturn(response);
        when(response.onStatus(any(), any(RestClient.ResponseSpec.ErrorHandler.class))).thenReturn(response);
    }
}
