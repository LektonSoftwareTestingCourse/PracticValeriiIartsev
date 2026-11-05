package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.*;

class GatewayApiTest extends ApiSupport {
    // docs/api-spec.md §3; code: TransactionRequestValidator, AuthorizationResponse.
    @Test
    void shouldApproveValidTransactionAndMatchSchema() {
        // given
        String pan = createCard();
        Map<String, Object> body = transaction(pan);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then
        assertApproved(response, (String) body.get("stan"));
        response.then().body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"));
        assertBalance(pan, 49_900);
        awaitTransaction(pan, (String) body.get("stan"));
    }

    static Stream<Arguments> requiredFields() {
        return Stream.of("mti", "stan", "pan", "processingCode", "currencyCode", "transmissionDateTime",
                        "terminalId", "merchantId", "mcc", "acquirerId", "amount")
                .flatMap(field -> Stream.of(Arguments.of(field, true), Arguments.of(field, false)));
    }

    @ParameterizedTest(name = "{0}, absent={1}")
    @MethodSource("requiredFields")
    void shouldIdentifyMissingOrNullField(String field, boolean absent) {
        // given
        Map<String, Object> body = transaction(createCard());
        if (absent) {
            body.remove(field);
        } else {
            body.put(field, null);
        }

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then: TransactionValidationFilter is the owner of this rejection.
        assertError(response, 400, "gateway");
        response.then().body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Field '" + field + "' is required"))
                .body("retryAfterMs", nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
            "merchantId", "mcc", "acquirerId"})
    void shouldRejectBlankRequiredString(String field) {
        // given
        Map<String, Object> body = transaction(createCard());
        body.put(field, " ");

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then
        assertError(response, 400, "gateway");
        response.then().body("message", equalTo("Field '" + field + "' is required"));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("mti", "0200", "Field 'mti' must be '0100'"),
                Arguments.of("mti", "010", "Field 'mti' must be '0100'"),
                Arguments.of("mti", "01000", "Field 'mti' must be '0100'"),
                Arguments.of("pan", "400000123456789", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "40000012345678901", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "400000123456789A", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("amount", -1, "Field 'amount' must be > 0"),
                Arguments.of("amount", 0, "Field 'amount' must be > 0"),
                Arguments.of("currencyCode", "64", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("currencyCode", "6430", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc", "541", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "54111", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "ABCD", "Field 'mcc' must be exactly 4 digits"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidValues")
    void shouldExplainEachInvalidClass(String field, Object value, String message) {
        // given
        Map<String, Object> body = transaction(createCard());
        body.put(field, value);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then
        assertError(response, 400, "gateway");
        response.then().body("message", equalTo(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "{\"amount\":\"abc\"}", "{\"transmissionDateTime\":\"2026-11-05\"}"})
    void shouldRejectMalformedOrUnparseableBody(String body) {
        // given / when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then
        assertError(response, 400, "gateway");
        response.then().body("message", equalTo("Request body must be valid JSON"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void shouldAcceptLowerAmountBoundaryWithOptionalFieldAbsent(int amount) {
        // given
        Map<String, Object> body = transaction(createCard());
        body.put("amount", amount);
        body.remove("terminalType");

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then
        assertApproved(response, (String) body.get("stan"));
    }

    @Test
    void shouldDistinguishCacheMissAndHit() {
        // given
        String pan = createCard();
        given().spec(GATEWAY).when().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("pan", equalTo(pan)).header("X-Cache", equalTo("MISS"));

        // when / then
        given().spec(GATEWAY).when().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("pan", equalTo(pan)).header("X-Cache", equalTo("HIT"))
                .header("Content-Type", containsString("application/json"));
    }

    @Test
    void shouldReturn429WithRetryAfterHeader() {
        // given: separate Gateway instance, same production filter, capacity=1.
        given().spec(RATE_LIMIT).body("{").when().post("/api/transactions");

        // when
        Response response = given().spec(RATE_LIMIT).body("{").when().post("/api/transactions");

        // then: TransactionRateLimitFilter.
        response.then().statusCode(429).header("Retry-After", equalTo("1"))
                .body("error", equalTo("RATE_LIMIT_EXCEEDED"))
                .body("message", equalTo("Too many requests. Try again later."))
                .body("retryAfterMs", equalTo(1000));
    }
}
