package com.processing.api.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

public abstract class ApiSupport {
    public static final String TIME = "2026-11-05T12:00:00Z";
    public static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final AtomicInteger STAN = new AtomicInteger(ThreadLocalRandom.current().nextInt(100_000));

    public static final RequestSpecification GATEWAY = specification("gateway", 18080);
    public static final RequestSpecification CARDS = specification("cards", 18081);
    public static final RequestSpecification SWITCH = specification("switch", 18082);
    public static final RequestSpecification AUTH = specification("auth", 18083);
    public static final RequestSpecification MERCHANT = specification("merchant", 18084);
    public static final RequestSpecification TERMINAL = specification("terminal", 18085);
    public static final RequestSpecification LOGGER = specification("logger", 18088);
    public static final RequestSpecification RATE_LIMIT = specification("rateLimit", 18090);
    public static final RequestSpecification BIN = specification("bin", 18096);
    public static final RequestSpecification NOTIFICATIONS = specification("notifications", 18097);
    public static final RequestSpecification FAULTS = specification("faults", 18099);

    private static RequestSpecification specification(String service, int port) {
        return new RequestSpecBuilder()
                .setBaseUri(System.getProperty("api." + service + ".url", "http://127.0.0.1:" + port))
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    public static String uniqueStan() {
        return String.format("%06d", STAN.getAndIncrement() % 1_000_000);
    }

    public static String uniqueRrn() {
        return String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }

    public static String uniqueMerchant() {
        return "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    public static Map<String, Object> cardBody() {
        return new LinkedHashMap<>(Map.of("bin", "400000", "cardholderName", "IVAN IVANOV",
                "currencyCode", "643", "dailyLimit", 10_000, "monthlyLimit", 100_000, "initialBalance", 50_000));
    }

    public static Map<String, Object> transaction(String pan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mti", "0100");
        body.put("stan", uniqueStan());
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", 100);
        body.put("currencyCode", "643");
        body.put("transmissionDateTime", TIME);
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", uniqueMerchant());
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        return body;
    }

    public static Map<String, Object> internalTransaction(String pan) {
        Map<String, Object> body = transaction(pan);
        body.put("issuerId", "ISS001");
        return body;
    }

    public static Map<String, Object> logBody(String pan) {
        Map<String, Object> body = internalTransaction(pan);
        body.put("id", UUID.randomUUID().toString());
        body.put("rrn", uniqueRrn());
        body.put("authCode", "ABC123");
        body.put("status", "APPROVED");
        body.put("processingTimeMs", 0);
        body.put("createdAt", TIME);
        return body;
    }

    public static Map<String, Object> createCard(Map<String, Object> body) {
        return given().spec(GATEWAY).body(body)
                .when().post("/api/cards")
                .then().statusCode(201).body("pan", matchesPattern("\\d{16}"))
                .body("id", notNullValue()).body("status", equalTo("ACTIVE"))
                .extract().jsonPath().getMap("");
    }

    public static String createCard() {
        return (String) createCard(cardBody()).get("pan");
    }

    public static String createCard(int balance, int dailyLimit, int monthlyLimit) {
        Map<String, Object> body = cardBody();
        body.put("initialBalance", balance);
        body.put("dailyLimit", dailyLimit);
        body.put("monthlyLimit", monthlyLimit);
        return (String) createCard(body).get("pan");
    }

    public static void patchCard(String pan, Map<String, Object> patch) {
        given().spec(CARDS).body(patch).when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200).body("pan", equalTo(pan));
    }

    public static void assertBalance(String pan, int balance) {
        given().spec(CARDS).when().get("/api/cards/{pan}", pan)
                .then().statusCode(200).body("pan", equalTo(pan)).body("availableBalance", equalTo(balance));
    }

    public static void assertApproved(Response response, String stan) {
        response.then().statusCode(200).contentType(ContentType.JSON)
                .body("stan", equalTo(stan)).body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo(System.getProperty("api.expectedApprovedCode", "00")))
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue()).body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    public static void assertDeclined(Response response, int httpStatus, String code, String reason, String stan) {
        response.then().statusCode(httpStatus).contentType(ContentType.JSON)
                .body("stan", equalTo(stan)).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason))
                .body("rrn", nullValue()).body("authCode", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    public static void assertError(Response response, int status, String service) {
        response.then().statusCode(status).contentType(ContentType.JSON)
                .body("error", not(isEmptyOrNullString())).body("message", not(isEmptyOrNullString()))
                .body("serviceName", equalTo(service)).body("timestamp", notNullValue());
    }

    public static Response search(String pan) {
        return given().spec(GATEWAY).queryParam("pan", pan)
                .when().get("/api/transactions/search");
    }

    public static void awaitTransaction(String pan, String stan) {
        await().alias("transaction delivered to Logger").atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> search(pan).then().statusCode(200)
                        .body("transactions.findAll { it.stan == '" + stan + "' }", hasSize(1)));
    }

    public static void fault(String service, String method, String path, int status, Object body) {
        given().spec(FAULTS).body(Map.of("service", service, "method", method, "path", path,
                        "status", status, "body", body))
                .when().post("/__rules").then().statusCode(200).body("status", equalTo("ok"));
    }

    @AfterEach
    void resetFaults() {
        given().spec(FAULTS).when().delete("/__rules").then().statusCode(200).body("status", equalTo("ok"));
    }
}
