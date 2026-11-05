package com.processing.api;

import com.processing.api.support.ApiSupport;
import com.processing.api.support.StackControl;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

class FailureRecoveryApiTest extends ApiSupport {
    @Test
    void shouldReturn503AndDegradedHealthWhenSwitchIsUnavailable() {
        // given
        Map<String, Object> body = transaction(createCard());
        StackControl.stop("switch");
        try {
            // when
            Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

            // then: DownstreamErrorFilter and HealthController.
            response.then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"))
                    .body("serviceName", equalTo("switch"))
                    .body("message", equalTo("Switch service is temporarily unavailable"));
            given().spec(GATEWAY).when().get("/health").then().statusCode(503)
                    .body("status", equalTo("degraded")).body("services.switch", equalTo("unavailable"));
        } finally {
            StackControl.start("switch");
        }
    }

    @Test
    void shouldCompensateBalanceAndNeverLogRolledBackApproval() {
        // given: tz/06-switch.md; RouteService rolls APPROVED back when publication fails.
        String pan = createCard();
        Map<String, Object> body = transaction(pan);
        StackControl.stop("rabbitmq");
        try {
            // when
            Response response = given().spec(GATEWAY).body(body).when().post("/api/transactions");

            // then
            assertDeclined(response, 200, "96", "System error", (String) body.get("stan"));
            assertBalance(pan, 50_000);
            assertNoLogDuring(pan, Duration.ofSeconds(5));
        } finally {
            StackControl.start("rabbitmq");
        }
        // The whole normal delivery window is checked again after recovery, catching late deliveries.
        assertNoLogDuring(pan, DELIVERY_TIMEOUT);
        String controlPan = createCard();
        Map<String, Object> control = transaction(controlPan);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            // A declined card avoids repeated reservations while probing publisher recovery.
            patchCard(controlPan, Map.of("status", "BLOCKED"));
            Response probe = given().spec(GATEWAY).body(control).when().post("/api/transactions");
            assertDeclined(probe, 200, "05", "CARD_BLOCKED", (String) control.get("stan"));
            search(controlPan).then().statusCode(200).body("total", greaterThanOrEqualTo(1));
        });
    }

    private void assertNoLogDuring(String pan, Duration duration) {
        await().alias("no transaction after rollback, including after broker recovery")
                .during(duration).atMost(duration.plusSeconds(2)).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> search(pan).then().statusCode(200)
                        .body("total", equalTo(0)).body("transactions", hasSize(0)));
    }
}
