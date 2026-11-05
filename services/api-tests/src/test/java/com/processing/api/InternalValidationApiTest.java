package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class InternalValidationApiTest extends ApiSupport {
    static Stream<Arguments> invalidAuthorizationFields() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("mti", "stan", "pan", "processingCode", "amount", "currencyCode",
                "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId", "issuerId")) {
            cases.add(Arguments.of(field, null));
        }
        for (Map.Entry<String, Integer> rule : Map.of("mti", 4, "stan", 6, "pan", 16, "processingCode", 6,
                "currencyCode", 3, "terminalId", 8, "merchantId", 15, "mcc", 4).entrySet()) {
            cases.add(Arguments.of(rule.getKey(), "1".repeat(rule.getValue() - 1)));
            cases.add(Arguments.of(rule.getKey(), "1".repeat(rule.getValue() + 1)));
            cases.add(Arguments.of(rule.getKey(), " "));
        }
        cases.add(Arguments.of("amount", -1));
        return cases.stream();
    }

    @ParameterizedTest(name = "Authorization {0}={1}")
    @MethodSource("invalidAuthorizationFields")
    void shouldRejectEachInvalidInternalAuthorizationField(String field, Object value) {
        // given: DTO rules differ from Gateway; issuerId is mandatory here.
        Map<String, Object> body = internalTransaction(createCard());
        body.put(field, value);

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then: Spring Boot error format, missing field detail is recorded as D-06.
        response.then().statusCode(400).body("status", equalTo(400)).body("error", equalTo("Bad Request"))
                .body("path", equalTo("/api/internal/authorize")).body("timestamp", notNullValue());
    }
}
