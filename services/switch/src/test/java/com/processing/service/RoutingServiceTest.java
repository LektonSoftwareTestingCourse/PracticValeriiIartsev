package com.processing.service;

import java.util.Map;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RoutingServiceTest {

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        var properties = new SwitchProperties(
                "1.0",
                Map.of("400000", "ISS001", "400001", "ISS002"),
                null,
                null,
                null,
                null,
                null,
                null
        );
        routingService = new RoutingService(properties);
    }

    @ParameterizedTest
    @CsvSource({"4000001234567899, ISS001", "4000011234567898, ISS002", "400000, ISS001"})
    void shouldResolveIssuerWhenBinIsKnown(String pan, String expectedIssuer) {
        // Act
        var issuer = routingService.getIssuerIdByPan(pan);

        // Assert
        assertThat(issuer).isEqualTo(expectedIssuer);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "40000", "9999991234567890"})
    void shouldRejectWhenPanIsMissingShortOrUnknown(String pan) {
        // Act
        var error = catchThrowable(() -> routingService.getIssuerIdByPan(pan));

        // Assert
        assertThat(error).isInstanceOf(UnknownBinException.class);
    }
}
