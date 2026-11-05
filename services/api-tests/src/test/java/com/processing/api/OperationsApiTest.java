package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class OperationsApiTest extends ApiSupport {
    static Stream<Arguments> healthEndpoints() {
        return Stream.of(Arguments.of(GATEWAY, "gateway"), Arguments.of(CARDS, "card-management"),
                Arguments.of(SWITCH, "switch"), Arguments.of(AUTH, "authorization"),
                Arguments.of(TERMINAL, "terminal-simulator"), Arguments.of(MERCHANT, "merchant-acquirer-simulator"),
                Arguments.of(LOGGER, "transaction-logger"));
    }

    @ParameterizedTest(name = "health {1}")
    @MethodSource("healthEndpoints")
    void shouldExposeServiceHealth(RequestSpecification service, String name) {
        // given / when
        Response response = given().spec(service).when().get("/health");

        // then
        response.then().statusCode(200).body("status", equalTo("ok")).body("service", equalTo(name));
    }

    @Test
    void shouldExposeDashboardCounters() {
        // given / when
        Response response = given().spec(GATEWAY).when().get("/api/dashboard/stats");

        // then: DashboardStatsResponse, counts can include other independent fixtures.
        response.then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(0))
                .body("approvedCount", greaterThanOrEqualTo(0)).body("declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", allOf(greaterThanOrEqualTo(0f), lessThanOrEqualTo(1f)))
                .body("totalAmount", notNullValue()).body("averageAmount", notNullValue())
                .body("avgProcessingTimeMs", notNullValue()).body("transactionsPerMinute", greaterThanOrEqualTo(0));
        org.junit.jupiter.api.Assertions.assertEquals(response.jsonPath().getInt("totalTransactions"),
                response.jsonPath().getInt("approvedCount") + response.jsonPath().getInt("declinedCount"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void shouldBoundRecentTransactions(int limit) {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("limit", limit).when().get("/api/dashboard/recent");

        // then
        response.then().statusCode(200).body("size()", lessThanOrEqualTo(limit))
                .body("id", everyItem(matchesPattern("[0-9a-f-]{36}")));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 501})
    void shouldValidateRecentLimit(int limit) {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("limit", limit).when().get("/api/dashboard/recent");

        // then
        assertError(response, 400, "transaction-logger");
        response.then().body("message", containsString("limit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void shouldReturnChartBuckets(String granularity) {
        // given
        Map<String, Object> body = logBody(createCard());
        given().spec(LOGGER).body(body).when().post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(body.get("id"))).body("status", equalTo("stored"));

        // when
        Response response = given().spec(GATEWAY).queryParam("granularity", granularity)
                .queryParam("from", "2026-11-05T00:00:00Z").queryParam("to", "2026-11-06T00:00:00Z")
                .when().get("/api/dashboard/charts");

        // then
        response.then().statusCode(200).body("size()", greaterThanOrEqualTo(1))
                .body("timestamp", everyItem(notNullValue())).body("total", everyItem(greaterThanOrEqualTo(1)));
    }

    @Test
    void shouldReturnEmptyChartRange() {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("from", "2098-01-01T00:00:00Z")
                .queryParam("to", "2098-01-02T00:00:00Z").when().get("/api/dashboard/charts");

        // then
        response.then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void shouldRejectInvalidChartGranularity() {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("granularity", "minute")
                .when().get("/api/dashboard/charts");

        // then
        assertError(response, 400, "transaction-logger");
        response.then().body("message", containsString("granularity must be hour or day"));
    }

    @ParameterizedTest
    @CsvSource({"400000,ISS001", "400001,ISS002", "400002,ISS003", "400003,ISS004", "400004,ISS005"})
    void shouldLookUpKnownBin(String bin, String issuer) {
        // given / when
        Response response = given().spec(BIN).when().get("/api/bin/{bin}", bin);

        // then: static BIN table participates in Authorization.
        response.then().statusCode(200).body("bin", equalTo(bin)).body("issuerId", equalTo(issuer))
                .body("issuerName", not(isEmptyOrNullString()));
    }

    @Test
    void shouldReturnEmptyBodyForUnknownBinAndInjectedBinFailure() {
        // given / when
        Response unknown = given().spec(BIN).when().get("/api/bin/999999");

        // then: empty error bodies are an explicit limitation of this controller.
        unknown.then().statusCode(404).body(isEmptyString());
        given().spec(BIN).queryParam("fail", true).when().get("/api/bin/400000")
                .then().statusCode(500).body(isEmptyString());
    }

    @Test
    void shouldReadNotificationPageWithoutDatabaseAccess() {
        // given / when
        Response response = given().spec(NOTIFICATIONS).queryParam("page", 0).queryParam("size", 1)
                .when().get("/api/notifications");

        // then: only the HTTP page contract is tested; delivery internals belong to module 6.
        response.then().statusCode(200).body("number", equalTo(0)).body("size", equalTo(1))
                .body("content.size()", lessThanOrEqualTo(1)).body("totalElements", greaterThanOrEqualTo(0));
    }
}
