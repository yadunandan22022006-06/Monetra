package com.monetra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the user is told after PayPal sends them back: only a payment PayPal really confirmed is "completed", with
 * its transaction id; one that is not approved, declined, held for review or short of the amount is never shown as
 * paid; a busy PayPal does not fail a payment for good; and pressing pay twice makes one order.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-payment-outcome",
                "spring.mail.host=localhost",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class PaymentOutcomeTest {

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

    @BeforeEach
    void paypalAnswersNormally() {
        PAYPAL.captureMode = "OK";
    }

    @AfterAll
    static void stop() {
        PAYPAL.stop();
    }

    private void user(String email, String phone, String name) {
        users.save(new UserAccount(email, new BCryptPasswordEncoder().encode("password-1"), phone, name));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static String pay(Http me, String phone, int amount) throws Exception {
        HttpResponse<String> order = me.post("/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"" + phone + "\",\"amountInr\":" + amount + "}");

        assertEquals(200, order.statusCode(), order.body());

        return order.body();
    }

    @Test
    void aPaymentThatIsNotApprovedIsNeverShownAsPaidAndCanBeConfirmedLater() throws Exception {
        user("out1@test.dev", "9000000181", "Out One");
        user("out2@test.dev", "9000000182", "Out Two");

        Http me = Http.login(port, "out1@test.dev", "password-1");
        Http other = Http.login(port, "out2@test.dev", "password-1");

        String orderId = Http.field(pay(me, "9000000182", 880), "orderId");

        PAYPAL.captureMode = "NOT_APPROVED";

        HttpResponse<String> early = me.post("/api/payments/capture/" + orderId, "{}");

        assertEquals(409, early.statusCode(), early.body());
        assertTrue(early.body().contains("not been approved"), early.body());

        String list = me.get("/api/payments").body();

        assertTrue(list.contains("\"status\":\"AWAITING_APPROVAL\""), list);
        assertFalse(list.contains("COMPLETED"), list);
        assertFalse(me.get("/api/notifications").body().contains("You sent"));
        assertFalse(other.get("/api/notifications").body().contains("You received"));

        /* The user approves on PayPal and the same page asks again. */
        PAYPAL.captureMode = "OK";

        HttpResponse<String> done = me.post("/api/payments/capture/" + orderId, "{}");

        assertTrue(done.body().contains("\"status\":\"COMPLETED\""), done.body());
        assertTrue(done.body().contains("\"transactionId\":\"CAP-" + orderId + "\""), done.body());

        /* It is in the history once, with its transaction id, and a refresh changes nothing. */
        assertTrue(me.post("/api/payments/capture/" + orderId, "{}").body().contains("\"status\":\"COMPLETED\""));

        String after = me.get("/api/payments").body();

        assertEquals(1, count(after, "\"paymentId\""), after);
        assertEquals(1, count(after, "\"transactionId\":\"CAP-" + orderId + "\""), after);
        assertEquals(1, count(me.get("/api/notifications").body(), "You sent"));
    }

    @Test
    void aDeclinedPaymentFailsWithAClearMessageAndNeverCountsAsPaid() throws Exception {
        user("out3@test.dev", "9000000183", "Out Three");
        user("out4@test.dev", "9000000184", "Out Four");

        Http me = Http.login(port, "out3@test.dev", "password-1");
        Http other = Http.login(port, "out4@test.dev", "password-1");

        String orderId = Http.field(pay(me, "9000000184", 880), "orderId");

        PAYPAL.captureMode = "DECLINED";

        HttpResponse<String> res = me.post("/api/payments/capture/" + orderId, "{}");

        assertEquals(502, res.statusCode(), res.body());
        assertTrue(res.body().contains("No money was taken"), res.body());

        String list = me.get("/api/payments").body();

        assertTrue(list.contains("\"status\":\"FAILED\""), list);
        assertTrue(list.contains("\"transactionId\":null"), list);
        assertFalse(list.contains("\"status\":\"COMPLETED\""), list);

        String notes = me.get("/api/notifications").body();

        assertTrue(notes.contains("did not go through"), notes);
        assertFalse(notes.contains("You sent"), notes);
        assertFalse(other.get("/api/notifications").body().contains("You received"));

        /* It stays failed: asking again does not turn it into a payment. */
        PAYPAL.captureMode = "OK";

        assertEquals(409, me.post("/api/payments/capture/" + orderId, "{}").statusCode());
        assertFalse(me.get("/api/payments").body().contains("\"status\":\"COMPLETED\""));

        /* The page's "check again" gets the record back, saying FAILED, not an error and not a success. */
        String paymentId = Http.field(me.get("/api/payments").body(), "paymentId");

        int capturesSoFar = PAYPAL.captures.get();

        HttpResponse<String> checked = me.post("/api/payments/" + paymentId + "/refresh", "{}");

        assertEquals(200, checked.statusCode(), checked.body());
        assertTrue(checked.body().contains("\"status\":\"FAILED\""), checked.body());
        assertFalse(checked.body().contains("\"status\":\"COMPLETED\""), checked.body());

        /* A failed payment is reported as it is: PayPal is not asked to capture it again. */
        assertEquals(capturesSoFar, PAYPAL.captures.get());
    }

    @Test
    void aCaptureForTheWrongAmountIsNotConfirmed() throws Exception {
        user("out5@test.dev", "9000000185", "Out Five");
        user("out6@test.dev", "9000000186", "Out Six");

        Http me = Http.login(port, "out5@test.dev", "password-1");
        Http other = Http.login(port, "out6@test.dev", "password-1");

        String orderId = Http.field(pay(me, "9000000186", 880), "orderId");

        PAYPAL.captureMode = "WRONG_AMOUNT";

        HttpResponse<String> res = me.post("/api/payments/capture/" + orderId, "{}");

        assertTrue(res.body().contains("\"status\":\"FAILED\""), res.body());
        assertFalse(res.body().contains("\"status\":\"COMPLETED\""), res.body());
        assertFalse(me.get("/api/notifications").body().contains("You sent"));
        assertFalse(other.get("/api/notifications").body().contains("You received"));
    }

    @Test
    void aPaymentPayPalIsStillReviewingIsPendingNotPaidAndNotFailed() throws Exception {
        user("out7@test.dev", "9000000187", "Out Seven");
        user("out8@test.dev", "9000000188", "Out Eight");

        Http me = Http.login(port, "out7@test.dev", "password-1");
        Http other = Http.login(port, "out8@test.dev", "password-1");

        String orderId = Http.field(pay(me, "9000000188", 880), "orderId");

        PAYPAL.captureMode = "PENDING";

        HttpResponse<String> res = me.post("/api/payments/capture/" + orderId, "{}");

        assertTrue(res.body().contains("\"status\":\"PENDING\""), res.body());
        assertTrue(res.body().contains("not confirmed yet"), res.body());
        assertTrue(res.body().contains("\"transactionId\":null"), res.body());
        assertFalse(me.get("/api/notifications").body().contains("You sent"));
        assertFalse(other.get("/api/notifications").body().contains("You received"));
    }

    @Test
    void aBusyPayPalDoesNotFailThePaymentForGood() throws Exception {
        user("out9@test.dev", "9000000189", "Out Nine");
        user("out10@test.dev", "9000000190", "Out Ten");

        Http me = Http.login(port, "out9@test.dev", "password-1");

        String orderId = Http.field(pay(me, "9000000190", 880), "orderId");

        PAYPAL.captureMode = "BUSY";

        HttpResponse<String> busy = me.post("/api/payments/capture/" + orderId, "{}");

        assertEquals(502, busy.statusCode(), busy.body());
        assertTrue(busy.body().contains("Nothing was lost"), busy.body());

        String list = me.get("/api/payments").body();

        assertTrue(list.contains("\"status\":\"AWAITING_APPROVAL\""), list);
        assertFalse(list.contains("\"status\":\"FAILED\""), list);
        assertFalse(me.get("/api/notifications").body().contains("did not go through"));

        /* PayPal is back: the very same payment is confirmed, once. */
        PAYPAL.captureMode = "OK";

        assertTrue(me.post("/api/payments/capture/" + orderId, "{}").body().contains("\"status\":\"COMPLETED\""));
        assertEquals(1, count(me.get("/api/notifications").body(), "You sent"));
    }

    @Test
    void pressingPayTwiceMakesOneOrderButADifferentPaymentIsANewOne() throws Exception {
        user("out11@test.dev", "9000000191", "Out Eleven");
        user("out12@test.dev", "9000000192", "Out Twelve");

        Http me = Http.login(port, "out11@test.dev", "password-1");

        String first = pay(me, "9000000192", 880);
        String second = pay(me, "9000000192", 880);

        assertEquals(Http.field(first, "paymentId"), Http.field(second, "paymentId"));
        assertEquals(Http.field(first, "orderId"), Http.field(second, "orderId"));
        assertEquals(Http.field(first, "approvalUrl"), Http.field(second, "approvalUrl"));
        assertEquals(1, count(me.get("/api/payments").body(), "\"paymentId\""), me.get("/api/payments").body());

        /* Another amount is another payment. */
        String other = pay(me, "9000000192", 990);

        assertNotEquals(Http.field(first, "paymentId"), Http.field(other, "paymentId"));
        assertEquals(2, count(me.get("/api/payments").body(), "\"paymentId\""));
    }

    @Test
    void aCancelledPaymentIsCancelledAndNeverCompleted() throws Exception {
        user("out13@test.dev", "9000000193", "Out Thirteen");
        user("out14@test.dev", "9000000194", "Out Fourteen");

        Http me = Http.login(port, "out13@test.dev", "password-1");

        String body = pay(me, "9000000194", 880);

        String paymentId = Http.field(body, "paymentId");

        HttpResponse<String> cancelled = me.post("/api/payments/" + paymentId + "/cancel", "{}");

        assertTrue(cancelled.body().contains("\"status\":\"CANCELLED\""), cancelled.body());

        /* A late capture of a cancelled order is refused and nothing is charged. */
        int before = PAYPAL.captures.get();

        assertEquals(409, me.post("/api/payments/capture/" + Http.field(body, "orderId"), "{}").statusCode());
        assertEquals(before, PAYPAL.captures.get());
        assertFalse(me.get("/api/payments").body().contains("\"status\":\"COMPLETED\""));
    }
}
