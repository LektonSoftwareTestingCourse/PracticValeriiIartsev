package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

class SimulatorApiTest extends ApiSupport {
    @ParameterizedTest
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    void shouldRunEachTerminalScenario(String scenario) {
        // given: simulator API selects from the pool; exact approve count is intentionally unspecified.
        createCard();
        String blocked = createCard();
        patchCard(blocked, Map.of("status", "BLOCKED"));

        // when
        Response response = given().spec(GATEWAY).body(Map.of("count", 1, "scenario", scenario, "tps", 1))
                .when().post("/api/simulator/terminal/run");

        // then: TerminalRunResponse.
        assertSimulation(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"grocery", "electronics", "restaurant", "travel"})
    void shouldRunEachMerchantScenario(String scenario) {
        // given
        createCard();

        // when
        Response response = given().spec(GATEWAY).body(Map.of("count", 1, "scenario", scenario, "tps", 1))
                .when().post("/api/simulator/merchant/run");

        // then: SimulatorResponse, scenario enum from controller DTO.
        assertSimulation(response);
    }

    private void assertSimulation(Response response) {
        response.then().statusCode(200).body("totalSubmitted", equalTo(1)).body("transactions", hasSize(1))
                .body("elapsedMs", greaterThanOrEqualTo(0))
                .body("transactions[0].status", oneOf("APPROVED", "DECLINED"))
                .body("transactions[0].responseCode", matchesPattern("\\d{2}"));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                response.jsonPath().getInt("approved") + response.jsonPath().getInt("declined"));
    }

    @ParameterizedTest
    @CsvSource({"terminal,count,0", "terminal,count,-1", "terminal,tps,0", "terminal,tps,-1",
            "terminal,scenario,unknown", "merchant,count,0", "merchant,count,-1", "merchant,tps,0",
            "merchant,scenario,unknown"})
    void shouldRejectInvalidSimulatorInputs(String simulator, String field, String value) {
        // given
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("count", 1, "tps", 1,
                "scenario", simulator.equals("terminal") ? "normal" : "grocery"));
        body.put(field, field.equals("scenario") ? value : Integer.valueOf(value));

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/simulator/{simulator}/run", simulator);

        // then
        assertError(response, 400, simulator.equals("terminal") ? "terminal-simulator" : "Merchant acquirer simulator");
        response.then().body("message", containsStringIgnoringCase(field));
    }

    @Test
    void shouldRejectMerchantScenarioWithNoMatchingMerchants() {
        // given
        createCard();

        // when
        Response response = given().spec(GATEWAY)
                .body(Map.of("count", 1, "scenario", "grocery", "mccCodes", java.util.List.of("0000")))
                .when().post("/api/simulator/merchant/run");

        // then
        assertError(response, 404, "Merchant acquirer simulator");
        response.then().body("error", equalTo("Resource not found"));
    }

    @Test
    void shouldExposeMerchantsOnlyOnDirectServiceRoute() {
        // given / when
        Response response = given().spec(MERCHANT).when().get("/api/simulator/merchants");

        // then: Gateway's /merchant/** does not match plural /merchants.
        response.then().statusCode(200).body("size()", greaterThanOrEqualTo(1))
                .body("mcc", everyItem(matchesPattern("\\d{4}")));
        given().spec(GATEWAY).when().get("/api/simulator/merchants").then().statusCode(404)
                .body("status", equalTo(404)).body("path", equalTo("/api/simulator/merchants"));
    }

    @Test
    void shouldReturn404ForUnknownAcquiringFee() {
        // given
        Map<String, Object> body = internalTransaction(createCard());

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/simulator/merchant/fee");

        // then
        assertError(response, 404, "Merchant acquirer simulator");
        response.then().body("error", equalTo("Resource not found"));
    }

    @Test
    void shouldReadFeeForTransactionCreatedByMerchantSimulator() {
        // given: fee records have no create API; the simulator is their public producer.
        createCard();
        Response simulation = given().spec(GATEWAY).body(Map.of("count", 1, "scenario", "grocery", "tps", 1))
                .when().post("/api/simulator/merchant/run");
        assertSimulation(simulation);
        String stan = simulation.jsonPath().getString("transactions[0].stan");
        String expression = "transactions.findAll { it.stan == '" + stan + "' }";
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() ->
                given().spec(GATEWAY).queryParam("limit", 500).when().get("/api/transactions/search")
                        .then().statusCode(200).body(expression + ".size()", greaterThanOrEqualTo(1)));
        Map<String, Object> tx = given().spec(GATEWAY).queryParam("limit", 500)
                .when().get("/api/transactions/search").jsonPath().getMap(expression + "[0]");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        for (String field : java.util.List.of("transmissionDateTime", "pan", "stan", "amount", "terminalId")) {
            body.put(field, tx.get(field));
        }

        // when
        Response response = given().spec(GATEWAY).body(body).when().post("/api/simulator/merchant/fee");

        // then: lookup is scoped to the complete key obtained from this simulator response.
        response.then().statusCode(200).body("acquirerFee", notNullValue());
        org.hamcrest.MatcherAssert.assertThat(response.jsonPath().getDouble("acquirerFee"), greaterThanOrEqualTo(0d));
    }

    @Test
    void shouldStartAndStopContinuousSimulationThroughApi() {
        // given
        createCard();
        try {
            // when / then: controller explicitly returns an empty 200 body.
            given().spec(GATEWAY).body(Map.of("tps", 1, "transactionType", "NORMAL"))
                    .when().post("/api/simulator/terminal/start-continuous")
                    .then().statusCode(200).body(isEmptyString());
        } finally {
            given().spec(GATEWAY).when().post("/api/simulator/terminal/stop")
                    .then().statusCode(200).body(isEmptyString());
        }
    }
}
