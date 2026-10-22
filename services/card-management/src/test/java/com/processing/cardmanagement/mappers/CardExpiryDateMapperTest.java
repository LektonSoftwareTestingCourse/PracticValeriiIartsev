package com.processing.cardmanagement.mappers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class CardExpiryDateMapperTest {

    @DisplayName("C-10, C-15: представление срока в формате MMyy")
    @ParameterizedTest
    @CsvSource({"2029-12, 1229", "2030-01, 0130"})
    void shouldFormatWhenExpiryCrossesYearBoundary(YearMonth expiry, String expected) {
        // Arrange
        var mapper = new CardExpiryDateMapper();

        // Act
        var formatted = mapper.asString(expiry);

        // Assert
        assertThat(formatted).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"1229, 2029-12", "0130, 2030-01"})
    void shouldParseWhenExpiryCrossesYearBoundary(String formatted, YearMonth expiry) {
        // Arrange
        var mapper = new CardExpiryDateMapper();

        // Act
        var parsed = mapper.asYearMonth(formatted);

        // Assert
        assertThat(parsed).isEqualTo(expiry);
    }
}
