package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class CardsApiTest extends ApiSupport {
    // Sources: CreateCardRequest, CardController, CardServiceImpl, GlobalExceptionHandler.
    @Test
    void shouldCreateReadPatchAndDeleteCard() {
        // given
        String pan = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("status", "BLOCKED", "dailyLimit", 2000,
                "monthlyLimit", 20_000, "availableBalance", 500)).when().patch("/api/cards/{pan}", pan);

        // then
        response.then().statusCode(200).body("pan", equalTo(pan)).body("status", equalTo("BLOCKED"))
                .body("dailyLimit", equalTo(2000)).body("monthlyLimit", equalTo(20_000))
                .body("availableBalance", equalTo(500)).body("expiryDate", matchesPattern("\\d{4}-(0[1-9]|1[0-2])"))
                .body("issuerId", equalTo("ISS001"));
        given().spec(GATEWAY).when().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("BLOCKED")).body("cardholderName", equalTo("IVAN IVANOV"));
        given().spec(GATEWAY).when().delete("/api/cards/{pan}", pan).then().statusCode(204).body(isEmptyString());
        assertError(given().spec(CARDS).when().get("/api/cards/{pan}", pan), 404, "card-management");
    }

    static Stream<Arguments> invalidCreateFields() {
        return Stream.of(
                Arguments.of("bin", null, "must not be blank"),
                Arguments.of("bin", " ", "must"),
                Arguments.of("bin", "40000", "BIN"),
                Arguments.of("bin", "4000000", "BIN"),
                Arguments.of("bin", "40000A", "BIN"),
                Arguments.of("cardholderName", null, "must not be blank"),
                Arguments.of("cardholderName", " ", "blank"),
                Arguments.of("cardholderName", "Ivan", "uppercase"),
                Arguments.of("cardholderName", "ИВАН", "uppercase"),
                Arguments.of("currencyCode", null, "must not be blank"),
                Arguments.of("currencyCode", "64", "exactly"),
                Arguments.of("currencyCode", "6430", "exactly"),
                Arguments.of("currencyCode", "ABC", "digits"),
                Arguments.of("dailyLimit", -1, "negative"),
                Arguments.of("monthlyLimit", -1, "negative"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidCreateFields")
    void shouldValidateEachCreateCardRule(String field, Object value, String messageFragment) {
        // given
        Map<String, Object> body = cardBody();
        body.put(field, value);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards");

        // then: CMS DTO currently omits the field name in its error (D-05).
        assertError(response, 400, "card-management");
        response.then().body("message", containsStringIgnoringCase(messageFragment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bin", "cardholderName", "currencyCode"})
    void shouldRejectAbsentRequiredCreateField(String field) {
        // given
        Map<String, Object> body = cardBody();
        body.remove(field);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards");

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", equalTo("must not be blank"));
    }

    @Test
    void shouldAllowZeroLimitsAndValidNamePunctuation() {
        // given
        Map<String, Object> body = cardBody();
        body.put("cardholderName", "O'NEIL-SMITH A.");
        body.put("dailyLimit", 0);
        body.put("monthlyLimit", 0);
        body.put("initialBalance", 0);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards");

        // then
        response.then().statusCode(201).body("dailyLimit", equalTo(0)).body("monthlyLimit", equalTo(0))
                .body("availableBalance", equalTo(0)).body("cardholderName", equalTo(body.get("cardholderName")));
    }

    @Test
    void shouldReturn404ForUnknownBinOnCreation() {
        // given
        Map<String, Object> body = cardBody();
        body.put("bin", "999999");

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards");

        // then
        assertError(response, 404, "card-management");
        response.then().body("error", equalTo("BinNotFoundException")).body("message", containsString("999999"));
    }

    @ParameterizedTest
    @CsvSource({"99,1", "100,0"})
    void shouldReserveAndRollbackAtBalanceBoundary(int amount, int expectedBalance) {
        // given
        String pan = createCard(100, 1000, 10_000);
        Map<String, Object> body = Map.of("pan", pan, "amount", amount, "rrn", uniqueRrn());

        // when
        Response reserve = given().spec(GATEWAY).body(body).when().post("/api/cards/{pan}/reserve", pan);

        // then
        reserve.then().statusCode(200).body("pan", equalTo(pan)).body("availableBalance", equalTo(expectedBalance));
        given().spec(GATEWAY).body(body).when().post("/api/cards/{pan}/rollback", pan).then().statusCode(200)
                .body("pan", equalTo(pan)).body("availableBalance", equalTo(100));
        assertBalance(pan, 100);
    }

    @Test
    void shouldRejectInsufficientReserveWithoutChangingBalance() {
        // given
        String pan = createCard(100, 1000, 10_000);

        // when
        Response response = given().spec(GATEWAY).body(Map.of("amount", 101, "rrn", uniqueRrn()))
                .when().post("/api/cards/{pan}/reserve", pan);

        // then
        assertError(response, 402, "card-management");
        response.then().body("error", equalTo("InsufficientFundsException"));
        assertBalance(pan, 100);
    }

    @Test
    void shouldRejectDuplicateReservationAndRepeatedRollback() {
        // given
        String pan = createCard();
        Map<String, Object> body = Map.of("pan", pan, "amount", 100, "rrn", uniqueRrn());
        given().spec(CARDS).body(body).when().post("/api/cards/{pan}/reserve", pan).then().statusCode(200)
                .body("availableBalance", equalTo(49_900));

        // when
        Response repeated = given().spec(GATEWAY).body(body).when().post("/api/cards/{pan}/reserve", pan);

        // then
        assertError(repeated, 409, "card-management");
        repeated.then().body("error", equalTo("ReservationAlreadyExistsException"));
        given().spec(CARDS).body(body).when().post("/api/cards/{pan}/rollback", pan).then().statusCode(200)
                .body("availableBalance", equalTo(50_000));
        Response rollback = given().spec(GATEWAY).body(body).when().post("/api/cards/{pan}/rollback", pan);
        assertError(rollback, 409, "card-management");
        rollback.then().body("error", equalTo("RollbackAlreadySatisfiedException"));
        assertBalance(pan, 50_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "rollback"})
    void shouldRejectMissingCardForFundsOperation(String operation) {
        // given
        String pan = "400000" + uniqueRrn().substring(0, 10);

        // when
        Response response = given().spec(GATEWAY).body(Map.of("pan", pan, "amount", 1, "rrn", uniqueRrn()))
                .when().post("/api/cards/{pan}/{operation}", pan, operation);

        // then
        assertError(response, 404, "card-management");
        response.then().body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void shouldRejectMissingReservation() {
        // given
        String pan = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("pan", pan, "amount", 1, "rrn", uniqueRrn()))
                .when().post("/api/cards/{pan}/rollback", pan);

        // then
        assertError(response, 404, "card-management");
        response.then().body("error", equalTo("ReservationNotFoundException"));
    }

    @ParameterizedTest
    @CsvSource({"reserve,0", "reserve,-1", "rollback,0", "rollback,-1"})
    void shouldValidatePositiveFundsAmount(String operation, int amount) {
        // given
        String pan = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("pan", pan, "amount", amount, "rrn", uniqueRrn()))
                .when().post("/api/cards/{pan}/{operation}", pan, operation);

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", containsString("greater than 0"));
        assertBalance(pan, 50_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345678901", "1234567890123", "12345678901A"})
    void shouldValidateRrn(String rrn) {
        // given
        String pan = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("amount", 1, "rrn", rrn))
                .when().post("/api/cards/{pan}/reserve", pan);

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", anyOf(containsString("12 digits"), containsString("blank")));
    }

    @ParameterizedTest
    @CsvSource({"dailyLimit,-1", "monthlyLimit,-1"})
    void shouldValidatePatchLimits(String field, int value) {
        // given
        String pan = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of(field, value)).when().patch("/api/cards/{pan}", pan);

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", containsStringIgnoringCase("negative"));
    }

    @Test
    void shouldRejectDailyLimitGreaterThanMonthlyOnPatch() {
        // given
        String pan = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("dailyLimit", 100_001))
                .when().patch("/api/cards/{pan}", pan);

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", equalTo("Daily limit can not be larger than monthly limit"));
    }

    @Test
    void shouldUpdateOnlySelectedPansInBulk() {
        // given
        String selected = createCard();
        String other = createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("pans", List.of(selected), "status", "INACTIVE"))
                .when().post("/api/cards/bulk-update");

        // then
        response.then().statusCode(200).body("updated", equalTo(1));
        given().spec(CARDS).when().get("/api/cards/{pan}", selected).then().statusCode(200)
                .body("status", equalTo("INACTIVE"));
        given().spec(CARDS).when().get("/api/cards/{pan}", other).then().statusCode(200)
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void shouldRejectBulkUpdateWithoutSelector() {
        // given / when
        Response response = given().spec(GATEWAY).body(Map.of("status", "ACTIVE")).when().post("/api/cards/bulk-update");

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", equalTo("Either bin or pans must be provided"));
    }

    @Test
    void shouldGenerateCardsWithRequestedBins() {
        // given / when
        Response response = given().spec(GATEWAY).body(Map.of("count", 2, "bins", List.of("400000", "400001")))
                .when().post("/api/cards/generate");

        // then
        response.then().statusCode(201).body("generated", equalTo(2)).body("cards", hasSize(2))
                .body("cards.bin", containsInAnyOrder("400000", "400001"))
                .body("cards.pan", everyItem(matchesPattern("\\d{16}")));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 10001})
    void shouldValidateGeneratorCount(int count) {
        // given / when
        Response response = given().spec(GATEWAY).body(Map.of("count", count, "bins", List.of("400000")))
                .when().post("/api/cards/generate");

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", not(isEmptyOrNullString()));
    }

    @Test
    void shouldRejectEmptyGeneratorBins() {
        // given / when
        Response response = given().spec(GATEWAY).body(Map.of("count", 1, "bins", List.of()))
                .when().post("/api/cards/generate");

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", equalTo("must not be empty"));
    }
}
