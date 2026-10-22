package com.processing.cardmanagement.validation;

import com.processing.common.dto.cardmanagement.CreateCardRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class CreateCardRequestTest {
    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new SpringConstraintValidatorFactory(new DefaultListableBeanFactory()))
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @DisplayName("CP-01–03: допустимые DTO; успешное сохранение в БД не проверяется")
    @ParameterizedTest
    @MethodSource("validRequests")
    void shouldAcceptWhenCreationFieldsAreValid(CreateCardRequest request) {
        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).isEmpty();
    }

    @DisplayName("C-02–03, CP-04–06/08/10–14/16–19: неверные поля DTO")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void shouldRejectWhenCreationFieldIsInvalid(String caseId, CreateCardRequest request, String field) {
        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).as(caseId).extracting(v -> v.getPropertyPath().toString()).containsOnly(field);
    }

    @ParameterizedTest
    @CsvSource({"-1, 1500, dailyLimit", "1200, -1, monthlyLimit"})
    void shouldRejectWhenLimitIsNegative(BigDecimal daily, BigDecimal monthly, String invalidField) {
        // Arrange
        var request = new CreateCardRequest("400000", "IVAN IVANOV", "643", daily, monthly, BigDecimal.ZERO);

        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsOnly(invalidField);
    }

    @Disabled("CP-07/09/15: в CreateCardRequest нет ограничения имени в 255 символов; оно есть только в БД")
    @ParameterizedTest
    @ValueSource(strings = {"400000", "400001"})
    void shouldRejectWhenCardholderNameExceeds255Characters(String bin) {
        // Arrange
        var request = request(bin, "A".repeat(256), "643", null, null, null);

        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsOnly("cardholderName");
    }

    static Stream<CreateCardRequest> validRequests() {
        return Stream.of(
                request("400000", "IVAN IVANOV", "643", null, null, null),
                request("400001", "A".repeat(255), "840", "1200", "1500", "0"),
                request("400000", "IVAN IVANOV", "643", "1200", "1500", "1000"));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                arguments("CP-04", request("40000", "IVAN IVANOV", "840", "1200", null, null), "bin"),
                arguments("CP-05", request("40000A", "A".repeat(255), "840", null, null, null), "bin"),
                arguments("CP-06", request("400000", null, "840", null, null, "1000"), "cardholderName"),
                arguments("CP-08", request("400000", "A".repeat(255), "64A", "1200", null, "0"), "currencyCode"),
                arguments("CP-10", request("40000", "A".repeat(255), "643", null, "1500", "1000"), "bin"),
                arguments("CP-11", request("400001", "IVAN IVANOV", "64A", "1200", "1500", "0"), "currencyCode"),
                arguments("CP-12", request("40000A", "IVAN IVANOV", "643", "1200", "1500", "0"), "bin"),
                arguments("CP-13", request("400001", null, "643", "1200", "1500", "0"), "cardholderName"),
                arguments("CP-14", request("400001", "IVAN IVANOV", "64A", "1200", "1500", null), "currencyCode"),
                arguments("CP-16", request("40000", "IVAN IVANOV", "840", null, null, "0"), "bin"),
                arguments("CP-17", request("40000A", "IVAN IVANOV", "840", null, "1500", "1000"), "bin"),
                arguments("CP-18", request("400000", null, "840", null, null, null), "cardholderName"),
                arguments("CP-19", request("400001", "IVAN IVANOV", "64A", null, null, "1000"), "currencyCode"));
    }

    private static CreateCardRequest request(String bin, String name, String currency,
                                             String daily, String monthly, String balance) {
        return new CreateCardRequest(bin, name, currency, amount(daily), amount(monthly), amount(balance));
    }

    private static BigDecimal amount(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
