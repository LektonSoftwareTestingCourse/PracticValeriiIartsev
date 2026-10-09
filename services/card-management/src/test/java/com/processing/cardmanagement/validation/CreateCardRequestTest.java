package com.processing.cardmanagement.validation;

import com.processing.common.dto.cardmanagement.CreateCardRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CreateCardRequestTest {

    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        // Создаем валидатор без поднятия контекста
        factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new SpringConstraintValidatorFactory(new DefaultListableBeanFactory()))
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @DisplayName("C-02, C-03: неверная длина и недесятичный BIN")
    @ParameterizedTest
    @CsvSource({"40000", "40000A"})
    void shouldRejectWhenBinIsInvalid(String bin) {
        // Arrange
        var request = new CreateCardRequest(bin, "IVAN IVANOV", "643", new BigDecimal("1200"),
                new BigDecimal("1500"), new BigDecimal("1000"));

        // Act
        var violations = validator.validate(request);

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsOnly("bin");
    }

    @DisplayName("CP-01–CP-19: валидация DTO")
    @ParameterizedTest(name = "{0}: expected invalid field = {7}")
    @CsvSource({
            "CP-01, 400000, normal, 643, default, default, default, none",
            "CP-02, 400001, max, 840, 1200, 1500, 0, none",
            "CP-03, 400000, normal, 643, 1200, 1500, 1000, none",
            "CP-04, 40000, normal, 840, 1200, default, default, bin",
            "CP-05, 40000A, max, 840, default, default, default, bin",
            "CP-06, 400000, missing, 840, default, default, 1000, cardholderName",
            "CP-07, 400001, too_long, 840, default, 1500, 1000, cardholderName",
            "CP-08, 400000, max, 64A, 1200, default, 0, currencyCode",
            "CP-09, 400001, too_long, 643, default, default, 0, cardholderName",
            "CP-10, 40000, max, 643, default, 1500, 1000, bin",
            "CP-11, 400001, normal, 64A, 1200, 1500, 0, currencyCode",
            "CP-12, 40000A, normal, 643, 1200, 1500, 0, bin",
            "CP-13, 400001, missing, 643, 1200, 1500, 0, cardholderName",
            "CP-14, 400001, normal, 64A, 1200, 1500, default, currencyCode",
            "CP-15, 400000, too_long, 840, 1200, default, default, cardholderName",
            "CP-16, 40000, normal, 840, default, default, 0, bin",
            "CP-17, 40000A, normal, 840, default, 1500, 1000, bin",
            "CP-18, 400000, missing, 840, default, default, default, cardholderName",
            "CP-19, 400001, normal, 64A, default, default, 1000, currencyCode"
    })
    void shouldValidateDtoConstraintsWhenPairwiseInputIsProvided(
            String caseId, String bin, String nameClass, String currency, String daily, String monthly,
            String balance, String invalidField) {
        // Arrange
        var name = switch (nameClass) {
            case "max" -> "A".repeat(255);
            case "too_long" -> "A".repeat(256);
            case "missing" -> null;
            default -> "IVAN IVANOV";
        };
        var request = new CreateCardRequest(bin, name, currency, amount(daily), amount(monthly), amount(balance));

        // Act
        var violations = validator.validate(request);

        // Assert
        if (invalidField.equals("none")) {
            assertThat(violations).as("%s: DTO validation only, not proof of successful creation", caseId).isEmpty();
        } else {
            assertThat(violations).as(caseId).extracting(v -> v.getPropertyPath().toString()).containsOnly(invalidField);
        }
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

    private BigDecimal amount(String value) {
        return value.equals("default") ? null : new BigDecimal(value);
    }
}
