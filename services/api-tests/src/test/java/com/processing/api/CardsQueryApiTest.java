package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class CardsQueryApiTest extends ApiSupport {
    private String createPrivateBin() {
        String bin = String.valueOf(ThreadLocalRandom.current().nextInt(600_000, 900_000));
        given().spec(CARDS).body(Map.of("bin", bin, "issuerId", "ISS" + bin))
                .when().post("/api/bins").then().statusCode(201)
                .body("bin", equalTo(bin)).body("issuerId", equalTo("ISS" + bin));
        return bin;
    }

    @Test
    void shouldCreateReadAndDeleteBinMappingAndRejectDuplicate() {
        // given
        String bin = createPrivateBin();

        // when
        Response response = given().spec(CARDS).body(Map.of("bin", bin, "issuerId", "ISS" + bin))
                .when().post("/api/bins");

        // then: endpoint exists in controller, is absent from Gateway routes.
        assertError(response, 409, "card-management");
        response.then().body("error", equalTo("BinAlreadyExistException"));
        given().spec(CARDS).when().get("/api/bins/{bin}", bin).then().statusCode(200)
                .body("issuerId", equalTo("ISS" + bin));
        given().spec(CARDS).when().get("/api/bins").then().statusCode(200).body("bin", hasItem(bin));
        given().spec(CARDS).when().delete("/api/bins/{bin}", bin).then().statusCode(204).body(isEmptyString());
        assertError(given().spec(CARDS).when().get("/api/bins/{bin}", bin), 404, "card-management");
        assertError(given().spec(CARDS).when().delete("/api/bins/{bin}", bin), 404, "card-management");
    }

    @Test
    void shouldApplyAllCardFiltersAndPaginationToPrivateBin() {
        // given
        String bin = createPrivateBin();
        Map<String, Object> body = cardBody();
        body.put("bin", bin);
        String active = (String) createCard(body).get("pan");
        String blocked = (String) createCard(body).get("pan");
        patchCard(blocked, Map.of("status", "BLOCKED"));

        // when
        Response response = given().spec(GATEWAY).queryParams(Map.of("bin", bin, "issuerId", "ISS" + bin,
                        "status", "ACTIVE", "limit", 1, "offset", 0,
                        "startDate", "2000-01-01T00:00:00Z", "endDate", "2099-01-01T00:00:00Z"))
                .when().get("/api/cards");

        // then
        response.then().statusCode(200).body("total", equalTo(1)).body("cards", hasSize(1))
                .body("cards[0].pan", equalTo(active)).body("cards[0].status", equalTo("ACTIVE"));
        given().spec(GATEWAY).queryParam("bin", bin).queryParam("limit", 1).queryParam("offset", 2)
                .when().get("/api/cards").then().statusCode(200).body("total", equalTo(2)).body("cards", hasSize(0));
        given().spec(GATEWAY).queryParam("bin", bin).queryParam("startDate", "2099-01-01T00:00:00Z")
                .when().get("/api/cards").then().statusCode(200).body("total", equalTo(0)).body("cards", hasSize(0));
        given().spec(GATEWAY).queryParam("bin", bin).queryParam("endDate", "2000-01-01T00:00:00Z")
                .when().get("/api/cards").then().statusCode(200).body("total", equalTo(0)).body("cards", hasSize(0));
    }

    @Test
    void shouldBulkUpdateByBinsAndRejectTwoSelectors() {
        // given
        String bin = createPrivateBin();
        Map<String, Object> body = cardBody();
        body.put("bin", bin);
        String pan = (String) createCard(body).get("pan");

        // when
        Response response = given().spec(GATEWAY).body(Map.of("bins", List.of(bin), "status", "INACTIVE"))
                .when().post("/api/cards/bulk-update");

        // then
        response.then().statusCode(200).body("updated", equalTo(1));
        given().spec(CARDS).when().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("INACTIVE"));
        Response invalid = given().spec(GATEWAY)
                .body(Map.of("bins", List.of(bin), "pans", List.of(pan), "status", "ACTIVE"))
                .when().post("/api/cards/bulk-update");
        assertError(invalid, 400, "card-management");
        invalid.then().body("message", equalTo("Only one of bin or pans must be provided"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10000})
    void shouldAcceptCardPageLimitBoundary(int limit) {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("issuerId", "NONEXIST").queryParam("limit", limit)
                .when().get("/api/cards");

        // then
        response.then().statusCode(200).body("total", equalTo(0)).body("cards", hasSize(0));
    }

    @Test
    void shouldReturn413ForCardPageLargerThanConfiguredMaximum() {
        // given / when
        Response response = given().spec(GATEWAY).queryParam("limit", 10001).when().get("/api/cards");

        // then: CardServiceImpl -> TooLargeLimitException, differs from generic documented 400.
        assertError(response, 413, "card-management");
        response.then().body("error", equalTo("TooLargeLimitException"));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,-1", "offset,-1", "bin,12345", "bin,1234567", "bin,12345A",
            "issuerId,lower", "issuerId,ABCDEFGHIJK"})
    void shouldRejectInvalidCardQueryParameter(String parameter, String value) {
        // given / when
        Response response = given().spec(GATEWAY).queryParam(parameter, value).when().get("/api/cards");

        // then: constraints on controller method parameters include the field name.
        assertError(response, 400, "card-management");
        response.then().body("message", containsString(parameter));
    }

    @ParameterizedTest(name = "card query mask={0}")
    @ValueSource(ints = {0, 63, 21, 42, 51, 12, 15, 48})
    void shouldCombineCardFiltersAndPaginationWithPairwiseCoverage(int mask) {
        // given: private issuer, two BINs, three cards; timestamp cutoffs lie between creation times.
        String firstBin = createPrivateBin();
        String issuer = "ISS" + firstBin;
        String secondBin = String.valueOf(Integer.parseInt(firstBin) + 1);
        given().spec(CARDS).body(Map.of("bin", secondBin, "issuerId", issuer)).when().post("/api/bins")
                .then().statusCode(201).body("bin", equalTo(secondBin));
        Map<String, Object> body = cardBody();
        body.put("bin", firstBin);
        Map<String, Object> first = createCard(body);
        Map<String, Object> second = createCard(body);
        patchCard((String) second.get("pan"), Map.of("status", "BLOCKED"));
        body.put("bin", secondBin);
        Map<String, Object> third = createCard(body);
        Instant a = Instant.parse((String) first.get("createdAt"));
        Instant b = Instant.parse((String) second.get("createdAt"));
        Instant c = Instant.parse((String) third.get("createdAt"));
        String start = a.plusNanos(Duration.between(a, b).toNanos() / 2).toString();
        String end = b.plusNanos(Duration.between(b, c).toNanos() / 2).toString();
        Map<String, Object> query = new LinkedHashMap<>(Map.of("issuerId", issuer));
        List<String> parameters = List.of("status", "bin", "startDate", "endDate");
        List<String> values = List.of("ACTIVE", firstBin, start, end);
        for (int i = 0; i < 4; i++) {
            if ((mask & (1 << i)) != 0) {
                query.put(parameters.get(i), values.get(i));
            }
        }
        int limit = (mask & 16) == 0 ? 1 : 2;
        int offset = (mask & 32) == 0 ? 0 : 1;
        query.put("limit", limit);
        query.put("offset", offset);
        // Truth table: first differs in start, second in status, third in BIN and end.
        int[] excludedBy = {4, 1, 2 | 8};
        List<String> pans = List.of((String) first.get("pan"), (String) second.get("pan"), (String) third.get("pan"));
        List<String> matching = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if ((mask & excludedBy[i]) == 0) {
                matching.add(pans.get(i));
            }
        }

        // when
        Response response = given().spec(GATEWAY).queryParams(query).when().get("/api/cards");

        // then: repository does not promise order, so only membership and page size are asserted.
        response.then().statusCode(200).body("total", equalTo(matching.size()))
                .body("cards", hasSize(Math.min(limit, Math.max(0, matching.size() - offset))))
                .body("cards.pan", everyItem(isIn(matching)));
    }
}
