package com.processing.common.dto.cardmanagement;

import com.processing.common.dto.annotations.Bin;
import com.processing.common.dto.annotations.DigitsOnly;
import com.processing.common.dto.annotations.ExactSize;
import com.processing.common.dto.annotations.NotNegative;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Request to create a new card")
public record CreateCardRequest(

    @NotBlank
    @Bin
    @Schema(description = "Bank Identification Number (BIN)", example = "400000")
    String bin,

    @NotBlank
    @Size(max = 255)
    @Pattern(
        regexp = "^[A-Z\\s\\-\\.']+$",
        message = "Cardholder name can contain only uppercase letters, spaces, ', dots and -"
    )
    @Schema(description = "Cardholder name", example = "IVAN IVANOV")
    String cardholderName,

    @NotBlank
    @ExactSize(3)
    @DigitsOnly
    @Schema(description = "Currency code", example = "643")
    String currencyCode,

    @NotNegative
    @Schema(description = "Daily limit in kopecks", example = "15000000")
    BigDecimal dailyLimit,

    @NotNegative
    @Schema(description = "Monthly limit in kopecks", example = "300000000")
    BigDecimal monthlyLimit,

    @Schema(description = "Initial balance in kopecks", example = "1000000000")
    BigDecimal initialBalance
) {}
