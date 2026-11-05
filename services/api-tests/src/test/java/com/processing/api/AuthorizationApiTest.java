package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class AuthorizationApiTest extends ApiSupport {
    // Sources: AuthServiceImpl, DeclineOutcome, AuthControllerImpl; tz/04-authorization.md.
    @ParameterizedTest(name = "{0}, gateway={2}")
    @CsvSource({"INACTIVE,05,false", "INACTIVE,05,true", "BLOCKED,05,false", "BLOCKED,05,true",
            "EXPIRED,54,false", "EXPIRED,54,true"})
    void shouldPreserveDeclineReasonAcrossPublicAndInternalApi(String status, String code, boolean gateway) {
        // given
        String pan = createCard();
        patchCard(pan, Map.of("status", status));
        Map<String, Object> body = internalTransaction(pan);

        // when
        Response response = given().spec(gateway ? GATEWAY : AUTH).body(body).when()
                .post(gateway ? "/api/transactions" : "/api/internal/authorize");

        // then
        assertDeclined(response, gateway ? 200 : 403, code, "CARD_" + status, (String) body.get("stan"));
        assertBalance(pan, 50_000);
    }

    @ParameterizedTest
    @CsvSource({"99,51,INSUFFICIENT_FUNDS,422", "100,00,APPROVED,200", "101,00,APPROVED,200"})
    void shouldCheckBalanceBoundary(int balance, String code, String reason, int status) {
        // given
        String pan = createCard(balance, 1000, 10_000);
        Map<String, Object> body = internalTransaction(pan);

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then
        if ("00".equals(code)) {
            assertApproved(response, (String) body.get("stan"));
            assertBalance(pan, balance - 100);
        } else {
            assertDeclined(response, status, code, reason, (String) body.get("stan"));
            assertBalance(pan, balance);
        }
    }

    @ParameterizedTest
    @CsvSource({"99,1000,61", "100,1000,00", "101,1000,00", "1000,99,61", "1000,100,00", "1000,101,00"})
    void shouldCheckDailyAndMonthlyLimitBoundaries(int daily, int monthly, String code) {
        // given
        String pan = createCard(10_000, daily, monthly);
        Map<String, Object> body = internalTransaction(pan);

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then: creation permits daily > monthly; each constraint is isolated here.
        if ("00".equals(code)) {
            assertApproved(response, (String) body.get("stan"));
            assertBalance(pan, 9900);
        } else {
            assertDeclined(response, 400, "61", "EXCEEDS_AMOUNT_LIMIT", (String) body.get("stan"));
            assertBalance(pan, 10_000);
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void shouldRejectUnknownCardThroughBothApiLayers(boolean gateway) {
        // given: create then delete, so PAN is unique and demonstrably absent.
        String pan = createCard();
        given().spec(CARDS).when().delete("/api/cards/{pan}", pan).then().statusCode(204).body(isEmptyString());
        Map<String, Object> body = internalTransaction(pan);

        // when
        Response response = given().spec(gateway ? GATEWAY : AUTH).body(body).when()
                .post(gateway ? "/api/transactions" : "/api/internal/authorize");

        // then
        assertDeclined(response, gateway ? 200 : 404, "14", "CARD_NOT_FOUND", (String) body.get("stan"));
    }

    @Test
    void shouldCheckExpiryDateIndependentlyFromExpiredStatus() {
        // given: ACTIVE card, request is beyond the last valid month.
        Map<String, Object> card = createCard(cardBody());
        Map<String, Object> body = internalTransaction((String) card.get("pan"));
        body.put("transmissionDateTime", "2099-01-01T00:00:00Z");

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then
        assertDeclined(response, 403, "54", "CARD_EXPIRED", (String) body.get("stan"));
    }

    @Test
    void shouldRejectUnknownBinAtSwitch() {
        // given
        Map<String, Object> body = transaction("999999" + uniqueRrn().substring(0, 10));

        // when
        Response response = given().spec(SWITCH).body(body).when().post("/api/internal/route");

        // then: RoutingService declines before Authorization.
        assertDeclined(response, 200, "14", "CARD_NOT_FOUND", (String) body.get("stan"));
        response.then().body("mti", equalTo("0110"));
    }

    static Stream<Arguments> cmsFailures() {
        return Stream.of(
                Arguments.of("GET", "", 400, 404, "14", "CARD_NOT_FOUND"),
                Arguments.of("GET", "", 402, 403, "05", "CARD_BLOCKED"),
                Arguments.of("GET", "", 503, 503, "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("GET", "", 500, 503, "96", "SERVICE_UNAVAILABLE"),
                Arguments.of("POST", "/reserve", 400, 404, "14", "CARD_NOT_FOUND"),
                Arguments.of("POST", "/reserve", 404, 404, "14", "CARD_NOT_FOUND"),
                Arguments.of("POST", "/reserve", 402, 422, "51", "INSUFFICIENT_FUNDS"),
                Arguments.of("POST", "/reserve", 409, 503, "96", "RESERVATION_FAILED"),
                Arguments.of("POST", "/reserve", 503, 503, "96", "SERVICE_UNAVAILABLE"));
    }

    @ParameterizedTest(name = "CMS {0} {1}: {2} -> {5}")
    @MethodSource("cmsFailures")
    void shouldTranslateDownstreamFailures(String method, String suffix, int cmsStatus,
                                         int httpStatus, String code, String reason) {
        // given: test-only HTTP proxy; all other calls use real services.
        String pan = createCard();
        Map<String, Object> body = internalTransaction(pan);
        fault("cms", method, "/api/cards/" + pan + suffix, cmsStatus, Map.of("error", "injected"));

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then: same outcomes can arise before reading the card and during reserve.
        assertDeclined(response, httpStatus, code, reason, (String) body.get("stan"));
        assertBalance(pan, 50_000);
    }

    @Test
    void shouldReturnUnknownReasonForMalformedSuccessfulCmsReply() {
        // given
        String pan = createCard();
        Map<String, Object> body = internalTransaction(pan);
        fault("cms", "GET", "/api/cards/" + pan, 200, Map.of());

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then: null status is handled explicitly in AuthServiceImpl.
        assertDeclined(response, 400, "05", "UNKNOWN_REASON", (String) body.get("stan"));
    }

    @Test
    void shouldDocumentLackOfAuthorizationDeduplication() {
        // given: exploratory regression, see defect D-02 in api-test-report.md.
        String pan = createCard();
        Map<String, Object> body = transaction(pan);
        Response first = given().spec(GATEWAY).body(body).when().post("/api/transactions");
        assertApproved(first, (String) body.get("stan"));

        // when
        Response repeated = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then
        assertApproved(repeated, (String) body.get("stan"));
        repeated.then().body("rrn", not(equalTo(first.jsonPath().getString("rrn"))));
        assertBalance(pan, 49_800);
    }
}
