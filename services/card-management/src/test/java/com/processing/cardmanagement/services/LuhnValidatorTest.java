package com.processing.cardmanagement.services;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.constraints.CreditCardNumber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private final LuhnValidator validator = new LuhnValidator();
    private ValidatorFactory independentValidatorFactory;

    @BeforeEach
    void setUp() {
        independentValidatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterEach
    void tearDown() {
        independentValidatorFactory.close();
    }

    @DisplayName("C-01, C-08: независимая проверка сгенерированного PAN")
    @ParameterizedTest
    @ValueSource(strings = {"400000", "400001", "555555"})
    void shouldGenerateValidPanWhenBinIsValid(String bin) {
        // Act
        var pan = validator.generatePan(bin);

        // Assert
        assertThat(pan).startsWith(bin).matches("\\d{16}");
        var violations = independentValidatorFactory.getValidator().validate(new GeneratedPan(pan));
        assertThat(violations).isEmpty();
    }

    @DisplayName("C-08: известные контрольные цифры")
    @ParameterizedTest
    @CsvSource({"4000001234567899, true", "4000001234567890, false", "4111111111111111, true", "5555555555554444, true"})
    void shouldCheckControlDigitWhenPanContainsDigits(String pan, boolean expected) {
        // Act
        var valid = validator.isValid(pan);

        // Assert
        assertThat(valid).isEqualTo(expected);
    }

    private record GeneratedPan(@CreditCardNumber String value) {
    }
}
