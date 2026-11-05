package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Executable requirements and risk probes, intentionally red. See report D-01..D-12. */
@Tag("known-defect")
class KnownContractDefectsTest extends ApiSupport {
    @Test
    void shouldReturnResponseMti0110D01() {
        // given
        Map<String, Object> body = transaction(createCard());

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then: docs/api-spec.md AuthorizationResponse.
        assertApproved(response, (String) body.get("stan"));
        response.then().body("mti", equalTo("0110"));
    }

    @Test
    void shouldNotDebitTwiceForIdenticalAuthorizationD02() {
        // given: exploratory financial retry risk from lecture 10, not a promised STAN deduplication contract.
        String pan = createCard();
        Map<String, Object> body = transaction(pan);
        Response first = given().spec(GATEWAY).body(body).when().post("/api/transactions");
        assertApproved(first, (String) body.get("stan"));

        // when
        Response second = given().spec(GATEWAY).body(body).when().post("/api/transactions");

        // then
        assertApproved(second, (String) body.get("stan"));
        assertBalance(pan, 49_900);
    }

    @Test
    void shouldReturnExpiryInDocumentedMmyyFormatD03() {
        // given / when
        Response response = given().spec(GATEWAY).body(cardBody()).when().post("/api/cards");

        // then: docs/api-spec.md Card.expiryDate.
        response.then().statusCode(201).body("expiryDate", matchesPattern("(0[1-9]|1[0-2])\\d{2}"));
    }

    @Test
    void shouldApplyDefaultLimitsAndBalanceD04() {
        // given: tz/05-card-management.md defaults, DTO marks these values optional.
        Map<String, Object> body = cardBody();
        body.remove("dailyLimit");
        body.remove("monthlyLimit");
        body.remove("initialBalance");

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards");

        // then
        response.then().statusCode(201).body("dailyLimit", equalTo(15_000_000))
                .body("monthlyLimit", equalTo(300_000_000)).body("availableBalance", notNullValue());
    }

    @Test
    void shouldNameInvalidFieldInCardErrorD05() {
        // given: practice 5, validation error must identify both field and rule.
        Map<String, Object> body = cardBody();
        body.put("dailyLimit", -1);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards");

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", containsString("dailyLimit"));
    }

    @Test
    void shouldExplainInvalidInternalAuthorizationFieldD06() {
        // given
        Map<String, Object> body = internalTransaction(createCard());
        body.put("stan", "1");

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then: practice 5, validation error identifies the rejected field.
        response.then().statusCode(400).body("message", containsString("stan"));
    }

    @Test
    void shouldRejectNullReserveAmountAsClientErrorD07() {
        // given: amount is necessary for a reservation; ReserveRequest lacks @NotNull.
        String pan = createCard();
        Map<String, Object> body = new LinkedHashMap<>(Map.of("rrn", uniqueRrn()));
        body.put("amount", null);

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/cards/{pan}/reserve", pan);

        // then
        assertError(response, 400, "card-management");
        response.then().body("message", containsString("amount"));
    }

    @Test
    void shouldRejectRollbackLargerThanReservationD08() {
        // given: rollback must not create money; practice-3 reservation state model.
        String pan = createCard();
        String rrn = uniqueRrn();
        given().spec(CARDS).body(Map.of("amount", 100, "rrn", rrn)).when().post("/api/cards/{pan}/reserve", pan)
                .then().statusCode(200).body("availableBalance", equalTo(49_900));

        // when
        Response response = given().spec(CARDS).body(Map.of("pan", pan, "amount", 101, "rrn", rrn))
                .when().post("/api/cards/{pan}/rollback", pan);

        // then
        org.junit.jupiter.api.Assertions.assertAll(
                () -> response.then().statusCode(400).body("error", notNullValue()),
                () -> assertBalance(pan, 49_900));
    }

    @Test
    void shouldRestoreLimitAfterRollbackD09() {
        // given: external balance compensation must restore the ability to spend the released amount.
        String pan = createCard(1000, 100, 1000);
        Map<String, Object> body = internalTransaction(pan);
        Response approved = given().spec(AUTH).body(body).when().post("/api/internal/authorize");
        assertApproved(approved, (String) body.get("stan"));
        given().spec(AUTH).body(Map.of("pan", pan, "amount", 100, "rrn", approved.jsonPath().getString("rrn")))
                .when().post("/api/internal/rollback").then().statusCode(200).body("status", equalTo("APPROVED"));
        body.put("stan", uniqueStan());

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then
        assertApproved(response, (String) body.get("stan"));
    }

    @Test
    void shouldDescribeInvalidDateRangeD10() {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("dateFrom", "2026-11-06")
                .queryParam("dateTo", "2026-11-05").when().get("/api/transactions/search");

        // then: class-level validation must not produce an empty message.
        assertError(response, 400, "transaction-logger");
        response.then().body("message", containsString("date"));
    }

    @Test
    void shouldInvalidateCardCacheAfterDeleteD11() {
        // given: caching must not expose a deleted card as an existing resource.
        String pan = createCard();
        given().spec(GATEWAY).when().get("/api/cards/{pan}", pan).then().statusCode(200).body("pan", equalTo(pan));
        given().spec(GATEWAY).when().delete("/api/cards/{pan}", pan).then().statusCode(204).body(isEmptyString());

        // when
        Response response = given().spec(GATEWAY).when().get("/api/cards/{pan}", pan);

        // then
        response.then().statusCode(404).body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void shouldPreserveDbUnavailableOutcomeAtHttpBoundaryD12() {
        // given: oversized numeric input via an HTTP dependency, no direct DB access.
        Map<String, Object> card = createCard(cardBody());
        String pan = (String) card.get("pan");
        Map<String, Object> virtualCard = new LinkedHashMap<>(card);
        virtualCard.put("availableBalance", new java.math.BigDecimal("1E100"));
        virtualCard.put("dailyLimit", new java.math.BigDecimal("1E100"));
        virtualCard.put("monthlyLimit", new java.math.BigDecimal("1E100"));
        fault("cms", "GET", "/api/cards/" + pan, 200, virtualCard);
        Map<String, Object> body = internalTransaction(pan);
        body.put("amount", new java.math.BigDecimal("1E80"));

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/authorize");

        // then: DeclineOutcome.DB_UNAVAILABLE and AuthControllerImpl's default mapping.
        assertDeclined(response, 400, "96", "DB_UNAVAILABLE", (String) body.get("stan"));
    }
}
