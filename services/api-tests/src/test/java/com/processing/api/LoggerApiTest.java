package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class LoggerApiTest extends ApiSupport {
    // Sources: InternalTransactionController, TransactionService.store, TransactionMapper.matches.
    @Test
    void shouldNotDuplicateTransactionOnRedelivery() {
        // given
        String pan = createCard();
        Map<String, Object> body = logBody(pan);
        String id = (String) body.get("id");
        Response first = given().spec(LOGGER).body(body).when().post("/api/internal/log");

        // when
        Response repeated = given().spec(LOGGER).body(body).when().post("/api/internal/log");
        Response found = search(pan);

        // then: execute every assertion even against the deliberately broken Logger.
        assertAll(
                () -> first.then().statusCode(201).body("id", equalTo(id)).body("status", equalTo("stored")),
                () -> repeated.then().statusCode(200).body("id", equalTo(id)).body("amount", equalTo(100))
                        .body("pan", equalTo(pan)).body("status", equalTo("APPROVED")),
                () -> found.then().statusCode(200).body("total", equalTo(1)).body("transactions", hasSize(1))
                        .body("transactions.findAll { it.id == '" + id + "' }", hasSize(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "stan", "rrn", "pan", "mti", "processingCode", "currencyCode", "terminalId",
            "terminalType", "merchantId", "mcc", "acquirerId", "issuerId", "acquiringFee", "status",
            "declineReason", "authCode", "processingTimeMs", "transmissionDateTime"})
    void shouldRejectSameIdWithDifferentBusinessData(String field) {
        // given
        String pan = createCard();
        Map<String, Object> original = logBody(pan);
        given().spec(LOGGER).body(original).when().post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(original.get("id"))).body("status", equalTo("stored"));
        Map<String, Object> changed = new java.util.LinkedHashMap<>(original);
        Map<String, Object> alternatives = Map.ofEntries(
                Map.entry("amount", 101), Map.entry("stan", uniqueStan()), Map.entry("rrn", uniqueRrn()),
                Map.entry("pan", "400001" + uniqueRrn().substring(0, 10)), Map.entry("mti", "0200"),
                Map.entry("processingCode", "100000"), Map.entry("currencyCode", "840"),
                Map.entry("terminalId", "TERM0002"), Map.entry("terminalType", "ATM"),
                Map.entry("merchantId", uniqueMerchant()), Map.entry("mcc", "5812"), Map.entry("acquirerId", "ACQ002"),
                Map.entry("issuerId", "ISS002"), Map.entry("acquiringFee", 1), Map.entry("status", "DECLINED"),
                Map.entry("declineReason", "CARD_BLOCKED"), Map.entry("authCode", "XYZ123"),
                Map.entry("processingTimeMs", 1), Map.entry("transmissionDateTime", "2026-11-06T12:00:00Z"));
        changed.put(field, alternatives.get(field));

        // when
        Response response = given().spec(LOGGER).body(changed).when().post("/api/internal/log");

        // then
        assertError(response, 409, "transaction-logger");
        response.then().body("error", equalTo("Transaction conflict"))
                .body("message", containsString((String) original.get("id")));
        search(pan).then().statusCode(200).body("transactions", hasSize(1))
                .body("transactions[0].id", equalTo(original.get("id"))).body("transactions[0].amount", equalTo(100));
    }

    @Test
    void shouldIgnoreCreatedAtAndDecimalScaleOnRedelivery() {
        // given
        Map<String, Object> body = logBody(createCard());
        given().spec(LOGGER).body(body).when().post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(body.get("id"))).body("status", equalTo("stored"));
        body.put("createdAt", "2026-11-06T12:00:00Z");
        body.put("amount", new java.math.BigDecimal("100.00"));

        // when
        Response response = given().spec(LOGGER).body(body).when().post("/api/internal/log");

        // then
        response.then().statusCode(200).body("id", equalTo(body.get("id"))).body("amount", equalTo(100))
                .body("createdAt", equalTo(TIME));
    }

    static Stream<Arguments> invalidLogFields() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("id", "mti", "stan", "pan", "processingCode", "amount", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId", "status", "transmissionDateTime", "createdAt")) {
            cases.add(Arguments.of(field, null, field));
        }
        for (String field : List.of("mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
                "merchantId", "mcc", "acquirerId")) {
            cases.add(Arguments.of(field, " ", field));
        }
        for (Map.Entry<String, Integer> rule : Map.of("mti", 4, "rrn", 12, "terminalId", 8, "terminalType", 10,
                "merchantId", 15, "acquirerId", 10, "issuerId", 10, "declineReason", 100, "authCode", 6).entrySet()) {
            cases.add(Arguments.of(rule.getKey(), "X".repeat(rule.getValue() + 1), rule.getKey()));
        }
        for (Map.Entry<String, Integer> rule : Map.of("stan", 6, "pan", 16, "processingCode", 6,
                "currencyCode", 3, "mcc", 4).entrySet()) {
            cases.add(Arguments.of(rule.getKey(), "1".repeat(rule.getValue() - 1), rule.getKey()));
            cases.add(Arguments.of(rule.getKey(), "1".repeat(rule.getValue() + 1), rule.getKey()));
            cases.add(Arguments.of(rule.getKey(), "A".repeat(rule.getValue()), rule.getKey()));
        }
        cases.add(Arguments.of("amount", 0, "amount"));
        cases.add(Arguments.of("amount", -1, "amount"));
        cases.add(Arguments.of("acquiringFee", -1, "acquiringFee"));
        cases.add(Arguments.of("processingTimeMs", -1, "processingTimeMs"));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidLogFields")
    void shouldIdentifyEveryInvalidLoggerField(String field, Object value, String message) {
        // given
        Map<String, Object> body = logBody(createCard());
        body.put(field, value);

        // when
        Response response = given().spec(LOGGER).body(body).when().post("/api/internal/log");

        // then
        assertError(response, 400, "transaction-logger");
        response.then().body("error", equalTo("Validation error")).body("message", containsString(message))
                .body("retryAfterMs", equalTo("0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"id\":\"not-uuid\"}", "{\"status\":\"UNKNOWN\"}"})
    void shouldRejectMalformedLoggerBody(String body) {
        // given / when
        Response response = given().spec(LOGGER).body(body).when().post("/api/internal/log");

        // then
        assertError(response, 400, "transaction-logger");
        response.then().body("error", equalTo("Invalid request body"))
                .body("message", equalTo("Request body is malformed or contains unsupported values"));
    }

    @Test
    void shouldRejectWrongMethodOnInternalLog() {
        // given / when
        Response response = given().spec(LOGGER).when().get("/api/internal/log");

        // then
        assertError(response, 405, "transaction-logger");
        response.then().body("error", equalTo("Method not allowed")).body("message", containsString("GET"));
    }
}
