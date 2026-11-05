package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class AdditionalValidationApiTest extends ApiSupport {
    static Stream<Arguments> invalidPanMethods() {
        return Stream.of("GET", "PATCH", "DELETE").flatMap(method -> Stream.of(
                Arguments.of(method, "400000123456789"), Arguments.of(method, "40000012345678901"),
                Arguments.of(method, "400000123456789A")));
    }

    @ParameterizedTest
    @MethodSource("invalidPanMethods")
    void shouldValidatePanForEachCardOperation(String method, String pan) {
        // given / when
        Response response = given().spec(GATEWAY).body("{}").when().request(method, "/api/cards/" + pan);

        // then: @Pan on each controller method, not the transaction Gateway validator.
        assertError(response, 400, "card-management");
        response.then().body("message", containsString("PAN number must contain exactly 16 digits"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    void shouldReturn404ForEachOperationOnMissingCard(String method) {
        // given: an owned PAN with a successful prior DELETE.
        String pan = createCard();
        given().spec(CARDS).when().delete("/api/cards/{pan}", pan).then().statusCode(204).body(isEmptyString());

        // when
        Response response = given().spec(GATEWAY).body("{}").when().request(method, "/api/cards/" + pan);

        // then
        assertError(response, 404, "card-management");
        String masked = pan.substring(0, 4) + "********" + pan.substring(12);
        response.then().body("error", equalTo("CardNotFoundException"))
                .body("message", equalTo("Card with PAN " + masked + " was not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INACTIVE", "BLOCKED", "EXPIRED"})
    void shouldRejectReserveForNonActiveStatus(String status) {
        // given
        String pan = createCard();
        patchCard(pan, Map.of("status", status));

        // when
        Response response = given().spec(GATEWAY).body(Map.of("amount", 1, "rrn", uniqueRrn()))
                .when().post("/api/cards/{pan}/reserve", pan);

        // then
        assertError(response, 409, "card-management");
        response.then().body("error", equalTo("IllegalStateException")).body("message", containsString(status));
        assertBalance(pan, 50_000);
    }

    static Stream<Arguments> invalidGeneratorBins() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(List.of()), Arguments.of(List.of("40000")),
                Arguments.of(List.of("4000000")), Arguments.of(List.of("40000A")));
    }

    @ParameterizedTest
    @MethodSource("invalidGeneratorBins")
    void shouldValidateGeneratorCollectionAndElements(Object bins) {
        // given
        Map<String, Object> body = new LinkedHashMap<>(Map.of("count", 1));
        body.put("bins", bins);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards/generate");

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", anyOf(containsString("empty"), containsString("6 digits")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "rollback"})
    void shouldRejectMissingRrnForEachFundsOperation(String operation) {
        // given
        String pan = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("pan", pan, "amount", 1))
                .when().post("/api/cards/{pan}/{operation}", pan, operation);

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", equalTo("must not be blank"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/transactions/search", "/api/transactions/export"})
    void shouldApplyValidationToBothReadRepresentations(String path) {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("limit", 501).when().get(path);

        // then: export ignores pagination for selection, but validates the shared filter DTO first.
        assertError(response, 400, "transaction-logger");
        response.then().body("message", containsString("limit must not exceed 500"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"transmissionDateTime", "pan", "stan", "terminalId"})
    void shouldValidateAcquiringFeeRequest(String field) {
        // given
        Map<String, Object> body = internalTransaction(createCard());
        body.remove(field);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/simulator/merchant/fee");

        // then
        assertError(response, 400, "Merchant acquirer simulator");
        response.then().body("message", containsString(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/internal/route", "/api/internal/authorize", "/api/internal/rollback"})
    void shouldRejectMalformedInternalJson(String path) {
        // given / when
        Response response = given().spec(path.equals("/api/internal/route") ? SWITCH : AUTH)
                .body("{").when().post(path);

        // then: default Spring error contract for these controllers.
        response.then().statusCode(400).body("status", equalTo(400)).body("error", equalTo("Bad Request"))
                .body("path", equalTo(path));
    }
}
