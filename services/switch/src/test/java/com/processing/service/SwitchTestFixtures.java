package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.SwitchProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SwitchTestFixtures {
    private SwitchTestFixtures() {
    }

    static SwitchProperties properties() {
        return new SwitchProperties("1.0", Map.of("400000", "ISS001"), "http://authorization.test",
                "http://logger.test", "http://acquirer.test", new SwitchProperties.HttpProperties(100, 100, 100),
                new SwitchProperties.RetryProperties(3, List.of(1L)), null);
    }

    static AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234567899", "000000", new BigDecimal("100"),
                "643", Instant.parse("2026-09-24T10:00:00Z"), "TERM0001", "POS", "MERCH00000000001", "5411",
                "ACQ001", "ISS001");
    }

    static AuthorizationResponse response(String status) {
        return new AuthorizationResponse("0110", "000001", "123456789012", "ABC123", "00", status, null, 12);
    }

    static TransactionRequest transaction() {
        var request = request();
        return new TransactionRequest(UUID.fromString("00000000-0000-0000-0000-000000000001"), request.mti(),
                request.stan(), "123456789012", request.pan(), request.processingCode(), request.amount(),
                request.currencyCode(), request.terminalId(), request.terminalType(), request.merchantId(), request.mcc(),
                request.acquirerId(), request.issuerId(), new BigDecimal("1.50"), TransactionStatus.APPROVED, null,
                "ABC123", 12, request.transmissionDateTime(), Instant.parse("2026-09-24T10:00:00.012Z"));
    }
}
