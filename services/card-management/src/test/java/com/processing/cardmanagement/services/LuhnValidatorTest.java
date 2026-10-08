package com.processing.cardmanagement.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private final LuhnValidator validator = new LuhnValidator();

    @DisplayName("C-01, C-08: независимая проверка сгенерированного PAN")
    @ParameterizedTest
    @ValueSource(strings = {"400000", "400001", "555555"})
    void shouldGenerateValidPanWhenBinIsValid(String bin) {
        // Arrange
        // The fixture is initialized in setUp().

        // Act
        var pan = validator.generatePan(bin);

        // Assert
        assertThat(pan).startsWith(bin).matches("\\d{16}");
        // Independent oracle: left-to-right weighted sum instead of calling the generator's validator.
        int checksum = IntStream.range(0, pan.length()).map(i -> {
            int digit = Character.digit(pan.charAt(i), 10);
            int weighted = digit * (i % 2 == 0 ? 2 : 1);
            return weighted / 10 + weighted % 10;
        }).sum();
        assertThat(checksum % 10).isZero();
    }

    @DisplayName("C-08: известные контрольные цифры")
    @ParameterizedTest
    @CsvSource({"4000001234567899, true", "4000001234567890, false", "4111111111111111, true", "5555555555554444, true"})
    void shouldCheckControlDigitWhenPanContainsDigits(String pan, boolean expected) {
        // Arrange
        // The fixture is initialized in setUp().

        // Act
        var valid = validator.isValid(pan);

        // Assert
        assertThat(valid).isEqualTo(expected);
    }

}
