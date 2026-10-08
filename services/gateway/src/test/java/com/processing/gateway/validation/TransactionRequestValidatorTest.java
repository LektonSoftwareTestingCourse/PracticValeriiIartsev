package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TransactionRequestValidatorTest {

    private TransactionRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TransactionRequestValidator();
    }

    @DisplayName("A-19: сумма ниже минимальной одной копейки")
    @ParameterizedTest
    @CsvSource({"-1", "0", "0.5"})
    void shouldRejectWhenAmountIsBelowOneKopeck(BigDecimal amount) {
        // Arrange
        var request = validRequest().amount(amount).build();

        // Act
        var error = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(error)
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("amount");
    }

    @DisplayName("A-20–A-21: суммы 1 и 2; дополнительный представитель 100")
    @ParameterizedTest
    @CsvSource({"1", "2", "100"})
    void shouldAcceptWhenRequestIsValid(BigDecimal amount) {
        // Arrange
        var request = validRequest().amount(amount).build();

        // Act
        var error = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(error).isNull();
    }

    @Test
    void shouldRejectWhenRequestIsMissing() {
        // Arrange
        // The fixture is initialized in setUp().

        // Act
        var error = catchThrowable(() -> validator.validate(null));

        // Assert
        assertThat(error)
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void shouldRejectWhenRequiredFieldIsInvalid(AuthorizationRequest request, String field) {
        // Arrange
        // The fixture is initialized in setUp().

        // Act
        var error = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(error)
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining(field);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                arguments(validRequest().mti("0200").build(), "mti"),
                arguments(validRequest().stan(null).build(), "stan"),
                arguments(validRequest().pan("400000123456789").build(), "pan"),
                arguments(validRequest().pan("40000012345678900").build(), "pan"),
                arguments(validRequest().pan("400000123456789X").build(), "pan"),
                arguments(validRequest().processingCode(" ").build(), "processingCode"),
                arguments(validRequest().amount(null).build(), "amount"),
                arguments(validRequest().currencyCode("64").build(), "currencyCode"),
                arguments(validRequest().currencyCode("6430").build(), "currencyCode"),
                arguments(
                        validRequest().transmissionDateTime(null).build(), "transmissionDateTime"),
                arguments(validRequest().terminalId("").build(), "terminalId"),
                arguments(validRequest().merchantId(null).build(), "merchantId"),
                arguments(validRequest().mcc("541").build(), "mcc"),
                arguments(validRequest().mcc("54111").build(), "mcc"),
                arguments(validRequest().mcc("54A1").build(), "mcc"),
                arguments(validRequest().acquirerId(null).build(), "acquirerId")
        );
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder()
                .mti("0100").stan("000001").pan("4000001234567899").processingCode("000000")
                .amount(new BigDecimal("100")).currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-09-24T12:00:00Z"))
                .terminalId("TERM0001").terminalType("POS").merchantId("MERCH0000000001")
                .mcc("5411").acquirerId("ACQ001");
    }
}
