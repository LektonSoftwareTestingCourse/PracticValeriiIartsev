package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class SearchApiTest extends ApiSupport {
    private static void store(Map<String, Object> body) {
        given().spec(LOGGER).body(body).when().post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(body.get("id"))).body("status", equalTo("stored"));
    }

    static Stream<Arguments> filters() {
        return Stream.of(
                Arguments.of("status", "APPROVED", "DECLINED"),
                Arguments.of("merchantId", "M00000000000001", "M00000000000002"),
                Arguments.of("issuerId", "ISS001", "ISS002"),
                Arguments.of("mcc", "5411", "5812"),
                Arguments.of("declineReason", "CARD_BLOCKED", "INSUFFICIENT_FUNDS"),
                Arguments.of("dateFrom", "2026-11-05", "2026-11-04T23:59:59Z"),
                Arguments.of("dateTo", "2026-11-05", "2026-11-06T00:00:00Z"));
    }

    @ParameterizedTest(name = "filter={0}")
    @MethodSource("filters")
    void shouldApplyEachDocumentedAndCodeOnlyFilter(String filter, String value, String different) {
        // given: identical PAN, but one deliberately different filter value.
        String pan = createCard();
        Map<String, Object> target = logBody(pan);
        Map<String, Object> distractor = logBody(pan);
        String field = filter.startsWith("date") ? "transmissionDateTime" : filter;
        target.put(field, filter.startsWith("date") ? TIME : value);
        distractor.put(field, different);
        store(target);
        store(distractor);

        // when
        Response response = given().spec(GATEWAY).queryParam("pan", pan).queryParam(filter, value)
                .when().get("/api/transactions/search");

        // then: TransactionFilter and TransactionSpecification are the sources of truth.
        response.then().statusCode(200).body("total", equalTo(1)).body("transactions", hasSize(1))
                .body("transactions[0].id", equalTo(target.get("id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "CARD BLOCKED", "card-blocked"})
    void shouldSearchDeclineReasonByLexemes(String query) {
        // given: a substring/lexeme, not exact comparison with the stored text.
        String pan = createCard();
        Map<String, Object> body = logBody(pan);
        body.put("status", "DECLINED");
        body.put("declineReason", "CARD_BLOCKED");
        store(body);

        // when
        Response response = given().spec(GATEWAY).queryParam("pan", pan).queryParam("declineReason", query)
                .when().get("/api/transactions/search");

        // then: V2__add_decline_reason_full_text_search.sql, observed only through HTTP.
        response.then().statusCode(200).body("transactions", hasSize(1))
                .body("transactions[0].declineReason", equalTo("CARD_BLOCKED"));
    }

    @Test
    void shouldFilterByPanAndReturnEmptyResultForOtherPan() {
        // given
        String pan = createCard();
        Map<String, Object> body = logBody(pan);
        store(body);

        // when
        Response response = search(createCard());

        // then
        response.then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
        search(pan).then().statusCode(200).body("transactions[0].id", equalTo(body.get("id")));
    }

    @ParameterizedTest
    @CsvSource({"1,0,1", "1,1,1", "2,1,2", "500,0,3", "1,3,0"})
    void shouldApplyLimitAndOffsetWithoutChangingTotal(int limit, int offset, int count) {
        // given: distinct createdAt values make ordering deterministic.
        String pan = createCard();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> body = logBody(pan);
            body.put("createdAt", "2026-11-05T12:00:0" + i + "Z");
            store(body);
        }

        // when
        Response response = given().spec(GATEWAY).queryParam("pan", pan)
                .queryParam("limit", limit).queryParam("offset", offset).when().get("/api/transactions/search");

        // then
        response.then().statusCode(200).body("total", equalTo(3)).body("transactions", hasSize(count));
        if (count > 0) {
            response.then().body("transactions[0].createdAt", equalTo("2026-11-05T12:00:0" + (2 - offset) + "Z"));
        }
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,-1", "limit,501", "offset,-1", "limit,abc", "offset,abc",
            "status,UNKNOWN", "dateFrom,not-a-date", "dateTo,2026-13-01"})
    void shouldIdentifyInvalidSearchParameter(String parameter, String value) {
        // given / when
        Response response = given().spec(GATEWAY).queryParam(parameter, value)
                .when().get("/api/transactions/search");

        // then
        assertError(response, 400, "transaction-logger");
        response.then().body("message", containsString(parameter));
    }

    @Test
    void shouldKeepDefaultPageSizeAtFifty() {
        // given: 51 unique IDs and one private PAN; no dependence on previous tests.
        String pan = createCard();
        for (int i = 0; i < 51; i++) {
            store(logBody(pan));
        }

        // when
        Response response = search(pan);

        // then
        response.then().statusCode(200).body("total", equalTo(51)).body("transactions", hasSize(50));
    }

    // Pairwise presence of seven optional filters, PAN always scopes the private fixture.
    // Each pair of bits appears as 00, 01, 10, 11; checked by the runner's coverage audit.
    @ParameterizedTest(name = "filter mask={0}")
    @ValueSource(ints = {0, 127, 85, 42, 51, 76, 15, 112})
    void shouldCombineSearchFiltersWithPairwiseCoverage(int mask) {
        // given: target differs from each distractor in exactly one dimension.
        String pan = createCard();
        List<String> parameters = List.of("status", "merchantId", "issuerId", "mcc", "declineReason", "dateFrom", "dateTo");
        List<String> values = List.of("DECLINED", "M00000000000001", "ISS001", "5411", "CARD_BLOCKED",
                "2026-11-05", "2026-11-05");
        Map<String, Object> target = logBody(pan);
        target.putAll(Map.of("status", "DECLINED", "merchantId", values.get(1), "issuerId", "ISS001", "mcc", "5411",
                "declineReason", "CARD_BLOCKED"));
        store(target);
        List<Object> alternatives = List.of("APPROVED", "M00000000000002", "ISS002", "5812", "INSUFFICIENT_FUNDS",
                "2026-11-04T23:59:59Z", "2026-11-06T00:00:00Z");
        for (int i = 0; i < parameters.size(); i++) {
            Map<String, Object> other = logBody(pan);
            other.putAll(target);
            other.put("id", java.util.UUID.randomUUID().toString());
            other.put("stan", uniqueStan());
            other.put(i < 5 ? parameters.get(i) : "transmissionDateTime", alternatives.get(i));
            store(other);
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("pan", pan);
        for (int i = 0; i < parameters.size(); i++) {
            if ((mask & (1 << i)) != 0) {
                query.put(parameters.get(i), values.get(i));
            }
        }

        // when
        Response response = given().spec(GATEWAY).queryParams(query).when().get("/api/transactions/search");

        // then: independent truth table: each enabled filter removes one distractor.
        int expected = 8 - Integer.bitCount(mask);
        response.then().statusCode(200).body("total", equalTo(expected)).body("transactions", hasSize(expected))
                .body("transactions.findAll { it.id == '" + target.get("id") + "' }", hasSize(1));
    }

    @Test
    void shouldExportOnlyOwnTransactionsAsCsv() {
        // given
        String pan = createCard();
        Map<String, Object> body = logBody(pan);
        store(body);

        // when
        Response response = given().spec(GATEWAY).accept("text/csv").queryParam("pan", pan)
                .when().get("/api/transactions/export");

        // then
        response.then().statusCode(200).contentType("text/csv")
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .body(containsString((String) body.get("id"))).body(containsString(pan));
        org.junit.jupiter.api.Assertions.assertEquals(2, response.asString().lines().count());
    }
}
