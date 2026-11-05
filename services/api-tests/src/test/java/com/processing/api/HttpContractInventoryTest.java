package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class HttpContractInventoryTest extends ApiSupport {
    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/switch-docs", "/logger-docs", "/terminal-docs", "/merchant-docs", "/cards-docs"})
    void shouldPublishEachRoutedOpenApiDocument(String path) {
        // given / when
        Response response = given().spec(GATEWAY).when().get(path);

        // then: application.yml routes and springdoc configuration.
        response.then().statusCode(200).body("openapi", matchesPattern("3\\.[01]\\.\\d+"))
                .body("info.title", not(isEmptyOrNullString())).body("paths", notNullValue());
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/simulator/terminal/start", "GET,/api/simulator/terminal/status"})
    void shouldRecordDocumentedButUnimplementedSimulatorRoutes(String method, String path) {
        // given / when
        Response response = given().spec(GATEWAY).body("{}").when().request(method, path);

        // then: OpenAPI and controller disagree; keep the observed absence visible in the report.
        assertError(response, 500, "terminal-simulator");
        response.then().body("message", containsString("No static resource"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/internal/route", "/api/internal/authorize", "/api/internal/rollback", "/api/internal/log",
            "/api/bin/400000", "/api/bins", "/api/notifications"})
    void shouldKeepNonRoutedInternalEndpointsAbsentAtGateway(String path) {
        // given / when
        Response response = given().spec(GATEWAY).when().get(path);

        // then: no Gateway predicate matches these paths.
        response.then().statusCode(404).body("status", equalTo(404)).body("path", equalTo(path))
                .body("error", equalTo("Not Found"));
    }
}
