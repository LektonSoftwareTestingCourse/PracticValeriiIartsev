package com.processing.api;

import com.processing.api.support.ApiSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class RollbackApiTest extends ApiSupport {
    @Test
    void shouldRollbackAuthorizationAndRejectSecondRollback() {
        // given
        String pan = createCard();
        Map<String, Object> tx = internalTransaction(pan);
        Response approved = given().spec(AUTH).body(tx).when().post("/api/internal/authorize");
        assertApproved(approved, (String) tx.get("stan"));
        Map<String, Object> body = Map.of("pan", pan, "amount", 100, "rrn", approved.jsonPath().getString("rrn"));

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/rollback");

        // then: AuthControllerImpl and RollbackResponse.
        response.then().statusCode(200).body("rrn", equalTo(body.get("rrn"))).body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED")).body("declineReason", nullValue());
        assertBalance(pan, 50_000);
        given().spec(AUTH).body(body).when().post("/api/internal/rollback").then().statusCode(409)
                .body("status", equalTo("DECLINED")).body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK")).body("rrn", equalTo(body.get("rrn")));
        assertBalance(pan, 50_000);
    }

    @Test
    void shouldRejectRollbackOfMissingTransaction() {
        // given
        String pan = createCard();
        Map<String, Object> body = Map.of("pan", pan, "amount", 100, "rrn", uniqueRrn());

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/rollback");

        // then
        response.then().statusCode(404).body("status", equalTo("DECLINED")).body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND")).body("rrn", equalTo(body.get("rrn")));
    }

    @ParameterizedTest
    @CsvSource({"400,404,14,TRANSACTION_NOT_FOUND", "404,404,14,TRANSACTION_NOT_FOUND",
            "409,409,05,ALREADY_ROLLED_BACK", "503,503,96,SERVICE_UNAVAILABLE", "422,503,96,ROLLBACK_FAILED"})
    void shouldTranslateRollbackFailures(int cmsStatus, int expectedStatus, String code, String reason) {
        // given
        String pan = createCard();
        Map<String, Object> body = Map.of("pan", pan, "amount", 100, "rrn", uniqueRrn());
        fault("cms", "POST", "/api/cards/" + pan + "/rollback", cmsStatus, Map.of("error", "injected"));

        // when
        Response response = given().spec(AUTH).body(body).when().post("/api/internal/rollback");

        // then
        response.then().statusCode(expectedStatus).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason))
                .body("rrn", equalTo(body.get("rrn"))).body("processingTimeMs", greaterThanOrEqualTo(0));
        assertBalance(pan, 50_000);
    }
}
