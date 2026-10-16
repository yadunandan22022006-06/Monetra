package com.monetra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money that makes no sense is refused with a clear 400, never a crash (500) and never a payment or a record:
 * zero, negative, far too large, not a number, missing, or aimed at yourself.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-money-validation",
                "spring.mail.host=localhost",
                "anthropic.api-key=",
                "ai.base-url=",
                "paypal.webhook-id=WH-TEST",
                "monetra.score.daily-job=false"
        })
class MoneyValidationTest {

    private static final FakePaypal PAYPAL = new FakePaypal();

    @DynamicPropertySource
    static void paypalProperties(DynamicPropertyRegistry registry) {
        registry.add("paypal.base-url", PAYPAL::baseUrl);
        registry.add("paypal.client-id", () -> "test-client");
        registry.add("paypal.client-secret", () -> "test-secret");
    }

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository users;

    @AfterAll
    static void stop() {
        PAYPAL.stop();
    }

    private Http signedIn() throws Exception {
        if (users.findByEmail("money@test.dev").isEmpty()) {
            users.save(new UserAccount("money@test.dev", new BCryptPasswordEncoder().encode("password-m"), "9000000061", "Money"));
            users.save(new UserAccount("other@test.dev", new BCryptPasswordEncoder().encode("password-o"), "9000000062", "Other"));
        }

        return Http.login(port, "money@test.dev", "password-m");
    }

    private int paymentCount(Http me) throws Exception {
        String list = me.get("/api/payments").body();

        return list.equals("[]") ? 0 : list.split("\"paymentId\"", -1).length - 1;
    }

    /** Every payment request that was NOT refused with a 400, so one run lists all the gaps. */
    private String notRefused(Http me, String[][] requests) throws Exception {
        StringBuilder gaps = new StringBuilder();

        for (String[] r : requests) {
            HttpResponse<String> res = me.post("/api/payments/create-order", r[1]);

            if (res.statusCode() != 400) {
                gaps.append("\n  ").append(r[0]).append(" -> ").append(res.statusCode()).append(' ').append(res.body(), 0, Math.min(120, res.body().length()));
            }
        }

        return gaps.toString();
    }

    private static String order(String to, String amount) {
        return "{\"method\":\"PHONE\",\"recipientPhone\":\"" + to + "\",\"amountInr\":" + amount + ",\"description\":\"check\",\"confirmRisk\":true}";
    }

    @Test
    void aPaymentOfNothingOrLessIsRefused() throws Exception {
        Http me = signedIn();

        int before = paymentCount(me);

        String[] amounts = {"0", "-5", "-0.01", "0.0", "null", "\"\"", "\"abc\"", "\"NaN\"", "[]", "{}"};
        String[][] requests = new String[amounts.length][];

        for (int i = 0; i < amounts.length; i++) {
            requests[i] = new String[] {"amount " + amounts[i], order("9000000062", amounts[i])};
        }

        assertEquals("", notRefused(me, requests), "payments that were not refused");
        assertEquals(before, paymentCount(me), "nothing was created");
    }

    @Test
    void aPaymentFarAboveTheLimitOrTooSmallToChargeIsRefused() throws Exception {
        Http me = signedIn();

        int before = paymentCount(me);

        String[] amounts = {"1000000001", "1e12", "99999999999999999999", "0.001"};
        String[][] requests = new String[amounts.length][];

        for (int i = 0; i < amounts.length; i++) {
            requests[i] = new String[] {"amount " + amounts[i], order("9000000062", amounts[i])};
        }

        assertEquals("", notRefused(me, requests), "payments that were not refused");
        assertEquals(before, paymentCount(me), "nothing was created");
    }

    @Test
    void aPaymentWithoutAValidRecipientOrToYourselfIsRefused() throws Exception {
        Http me = signedIn();

        int before = paymentCount(me);

        String[][] requests = {
                {"empty recipient", order("", "100")},
                {"too short a number", order("123", "100")},
                {"your own number", order("9000000061", "100")},
                {"unknown method", "{\"method\":\"TELEPORT\",\"recipientPhone\":\"9000000062\",\"amountInr\":100}"},
                {"empty request", "{}"}
        };

        assertEquals("", notRefused(me, requests), "payments that were not refused");
        assertEquals(before, paymentCount(me), "nothing was created");
    }

    @Test
    void anOrderThatDoesNotExistCannotBeCapturedOrCancelled() throws Exception {
        Http me = signedIn();

        assertTrue(me.post("/api/payments/capture/ORDER-DOES-NOT-EXIST", "{}").statusCode() >= 400);
        assertTrue(me.post("/api/payments/not-a-payment/cancel", "{}").statusCode() >= 400);
    }

    @Test
    void aCancelledPaymentCannotBeCapturedAfterwards() throws Exception {
        Http me = signedIn();

        HttpResponse<String> created = me.post("/api/payments/create-order", order("9000000062", "50"));

        assertEquals(200, created.statusCode(), created.body());

        String paymentId = Http.field(created.body(), "paymentId");
        String orderId = Http.field(created.body(), "orderId");

        assertEquals(200, me.post("/api/payments/" + paymentId + "/cancel", "{}").statusCode());

        HttpResponse<String> late = me.post("/api/payments/capture/" + orderId, "{}");

        assertFalse(late.body().contains("\"status\":\"COMPLETED\""), "a cancelled payment never becomes completed: " + late.body());
        assertFalse(me.get("/api/payments").body().contains("\"status\":\"COMPLETED\""));
    }

    @Test
    void savedRecordsWithImpossibleMoneyAreRefusedAndNothingIsSaved() throws Exception {
        Http me = signedIn();

        String good = "{\"limits\":{\"daily\":500},\"expenses\":[{\"id\":1,\"name\":\"Tea\",\"amount\":20,\"date\":\"2026-10-16\"}]}";

        assertEquals(200, me.put("/api/data", good).statusCode());

        String[] bad = {
                "{\"limits\":{\"daily\":-1}}",
                "{\"limits\":{\"daily\":1e12}}",
                "{\"limits\":{\"daily\":\"lots\"}}",
                "{\"expenses\":[{\"id\":2,\"name\":\"Bad\",\"amount\":-20,\"date\":\"2026-10-16\"}]}",
                "{\"expenses\":[{\"id\":2,\"name\":\"Bad\",\"amount\":1e15,\"date\":\"2026-10-16\"}]}",
                "{\"expenses\":[{\"id\":2,\"name\":\"Bad\",\"amount\":\"twenty\",\"date\":\"2026-10-16\"}]}",
                "{\"profile\":{\"balance\":\"rich\"}}",
                "{\"profile\":{\"monthlyIncome\":-100}}",
                "{\"savings\":[{\"id\":3,\"name\":\"Bad\",\"amount\":-1}]}",
                "{\"loans\":[{\"name\":\"Bad\",\"amount\":1e15,\"due\":\"2026-11-05\"}]}",
                "[]",
                "\"text\"",
                "not json at all"
        };

        StringBuilder gaps = new StringBuilder();

        for (String body : bad) {
            HttpResponse<String> r = me.put("/api/data", body);

            if (r.statusCode() != 400) {
                gaps.append("\n  ").append(body).append(" -> ").append(r.statusCode()).append(' ').append(r.body(), 0, Math.min(100, r.body().length()));
            }
        }

        assertEquals("", gaps.toString(), "records that were not refused");

        String kept = me.get("/api/data").body();

        assertTrue(kept.contains("\"name\":\"Tea\"") && kept.contains("500"), "the good records are still there: " + kept);
    }

    @Test
    void zeroAndLargeRecordsAreAcceptedAndComeBackExactly() throws Exception {
        Http me = signedIn();

        String edge = "{\"limits\":{\"daily\":0,\"monthly\":1000000000},\"profile\":{\"balance\":0,\"monthlyIncome\":0},"
                + "\"expenses\":[{\"id\":9,\"name\":\"Big\",\"amount\":999999999.99,\"date\":\"2026-10-16\"}]}";

        assertEquals(200, me.put("/api/data", edge).statusCode());

        String back = me.get("/api/data").body();

        assertTrue(back.contains("999999999.99"), back);
        assertTrue(back.contains("1000000000"), back);
        assertEquals(200, me.get("/api/guardian/overview").statusCode(), "the Guardian copes with zero limits and huge amounts");
        assertEquals(200, me.get("/api/guardian/score").statusCode());
        assertEquals(200, me.get("/api/guardian/forecast").statusCode());
    }
}
