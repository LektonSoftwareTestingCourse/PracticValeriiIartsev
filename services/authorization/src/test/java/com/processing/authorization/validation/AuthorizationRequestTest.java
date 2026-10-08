package com.processing.authorization.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestTest {

    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @CsvSource({"0", "0.5"})
    void shouldRejectWhenAmountIsBelowContractMinimum(BigDecimal amount) {
        // Arrange
        var request = request(amount);

        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsOnly("amount");
    }

    @ParameterizedTest
    @CsvSource({"1", "2"})
    void shouldAcceptWhenAmountMeetsContractMinimum(BigDecimal amount) {
        // Arrange
        var request = request(amount);

        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "-1")
    void shouldRejectWhenAmountIsMissingOrNegative(String value) {
        // Arrange
        var request = request(value == null ? null : new BigDecimal(value));

        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsOnly("amount");
    }

    private AuthorizationRequest request(BigDecimal amount) {
        return new AuthorizationRequest(
                "0100",
                "000001",
                "4000001234567899",
                "000000",
                amount,
                "643",
                Instant.parse("2026-09-24T12:00:00Z"),
                "TERM0001",
                "POS",
                "MERCH0000000001",
                "5411",
                "ACQ001",
                "ISS001"
        );
    }
}
