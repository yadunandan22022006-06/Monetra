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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paying must never sign anyone out. Whatever happens on the way back from PayPal (approve, cancel, a refresh that
 * repeats the return, PayPal's own webhook), the session stays valid; only the Log Out button ends it, and only by a
 * POST. And a page left open for one person cannot act as another who signed in later in another tab.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-session-stability",
                "spring.mail.host=localhost",
                "anthropic.api-key=",
                "ai.base-url=",
                "paypal.webhook-id=WH-TEST",
                "monetra.score.daily-job=false"
        })
class SessionStabilityTest {

    private static final FakePaypal PAYPAL = new FakePaypal();

    private static final Map<String, String> GOOD = Map.of("PAYPAL-TRANSMISSION-ID", "good");

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

    private void user(String email, String phone, String name) {
        users.save(new UserAccount(email, new BCryptPasswordEncoder().encode("password-1"), phone, name));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static void stillSignedIn(Http http, String when) throws Exception {
        HttpResponse<String> me = http.get("/api/auth/me");

        assertEquals(200, me.statusCode(), "still signed in " + when + ": " + me.body());
    }

    @Test
    void successCancelAndRepeatedReturnsNeverEndTheSession() throws Exception {
        user("stay1@test.dev", "9000000171", "Stay One");
        user("stay2@test.dev", "9000000172", "Stay Two");

        Http me = Http.login(port, "stay1@test.dev", "password-1");

        /* A payment that is approved: creating, confirming, and the page being refreshed on the return address. */
        HttpResponse<String> order = me.post("/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"9000000172\",\"amountInr\":880}");

        assertEquals(200, order.statusCode(), order.body());
        stillSignedIn(me, "after creating the order");

        String orderId = Http.field(order.body(), "orderId");

        assertTrue(me.post("/api/payments/capture/" + orderId, "{}").body().contains("\"status\":\"COMPLETED\""));
        stillSignedIn(me, "after the payment was confirmed");

        for (int refresh = 0; refresh < 3; refresh++) {
            assertTrue(me.post("/api/payments/capture/" + orderId, "{}").body().contains("\"status\":\"COMPLETED\""));
        }

        stillSignedIn(me, "after refreshing the return page");

        /* One payment, one confirmation, however many times the return page was opened. */
        String list = me.get("/api/payments").body();

        assertEquals(1, count(list, "\"paymentId\""), list);
        assertEquals(1, count(me.get("/api/notifications").body(), "You sent"), me.get("/api/notifications").body());

        /* A payment that is cancelled on PayPal: the return goes to the cancel address, twice. */
        HttpResponse<String> second = me.post("/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"9000000172\",\"amountInr\":880}");

        String paymentId = Http.field(second.body(), "paymentId");

        assertEquals(200, me.post("/api/payments/" + paymentId + "/cancel", "{}").statusCode());
        stillSignedIn(me, "after cancelling");

        assertTrue(me.post("/api/payments/" + paymentId + "/cancel", "{}").statusCode() < 500);
        stillSignedIn(me, "after cancelling again");

        /* Cancelled stays cancelled: it can no longer be confirmed. */
        assertFalse(me.post("/api/payments/capture/" + Http.field(second.body(), "orderId"), "{}").body()
                .contains("\"status\":\"COMPLETED\""));
        stillSignedIn(me, "after trying to confirm a cancelled payment");
    }

    @Test
    void anActionOnTheReturnPageThatFailsLeavesTheSessionAlone() throws Exception {
        user("stay3@test.dev", "9000000173", "Stay Three");

        Http me = Http.login(port, "stay3@test.dev", "password-1");

        /* An order that does not exist, someone else's payment, and a made-up reference. */
        assertTrue(me.post("/api/payments/capture/ORDER-DOES-NOT-EXIST", "{}").statusCode() >= 400);
        assertTrue(me.post("/api/payments/not-a-payment/cancel", "{}").statusCode() >= 400);

        stillSignedIn(me, "after failed return actions");
    }

    @Test
    void paypalsOwnWebhookNeverEndsAnyonesSessionAndConfirmsOnce() throws Exception {
        user("stay4@test.dev", "9000000174", "Stay Four");
        user("stay5@test.dev", "9000000175", "Stay Five");

        Http sender = Http.login(port, "stay4@test.dev", "password-1");
        Http receiver = Http.login(port, "stay5@test.dev", "password-1");

        String orderId = Http.field(sender.post("/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"9000000175\",\"amountInr\":880}").body(), "orderId");

        String event = "{\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"CAP-SESSION\","
                + "\"amount\":{\"currency_code\":\"USD\",\"value\":\"10.00\"},"
                + "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + orderId + "\"}}}}";

        /* The webhook arrives with no cookie at all: it works by PayPal's signature, not by anybody's session. */
        assertEquals(200, new Http(port).send("POST", "/api/paypal/webhook", event, GOOD).statusCode());

        stillSignedIn(sender, "after PayPal's webhook");
        stillSignedIn(receiver, "after PayPal's webhook");

        /* The browser then comes back too: it gets the stored result and nothing is confirmed or sent twice. */
        assertTrue(sender.post("/api/payments/capture/" + orderId, "{}").body().contains("\"status\":\"COMPLETED\""));

        assertEquals(1, count(sender.get("/api/notifications").body(), "You sent"), sender.get("/api/notifications").body());
        assertEquals(1, count(receiver.get("/api/notifications").body(), "You received"), receiver.get("/api/notifications").body());
        assertEquals(1, count(sender.get("/api/payments").body(), "\"paymentId\""));
    }

    @Test
    void onlyAPostToLogoutEndsTheSessionSoNoLinkOrPageLoadCanDoIt() throws Exception {
        user("stay6@test.dev", "9000000176", "Stay Six");

        Http me = Http.login(port, "stay6@test.dev", "password-1");

        /* Opening the address (a link, an image, the return page) cannot log anyone out. */
        assertEquals(405, me.get("/api/auth/logout").statusCode());
        assertEquals(200, me.get("/").statusCode());
        assertEquals(200, me.get("/?paypal=return&ref=X&token=Y").statusCode());

        stillSignedIn(me, "after opening the logout address and the return address");

        /* The Log Out button's request does end it, and a second one is harmless. */
        assertEquals(200, me.post("/api/auth/logout", "{}").statusCode());
        assertEquals(401, me.get("/api/auth/me").statusCode());
        assertEquals(200, me.post("/api/auth/logout", "{}").statusCode());
    }

    @Test
    void aTabShowingSomeoneElseIsRefusedAndNothingOfTheirsIsChanged() throws Exception {
        user("tab-a@test.dev", "9000000177", "Tab A");
        user("tab-b@test.dev", "9000000178", "Tab B");

        /* One browser, two tabs, one login cookie: signing in as B in the second tab changes the cookie for both. */
        Http browser = Http.login(port, "tab-b@test.dev", "password-1");

        String bRecords = "{\"limits\":{\"daily\":500},\"expenses\":[{\"id\":1,\"name\":\"B's tea\",\"amount\":20,\"date\":\"2026-10-17\"}]}";

        assertEquals(200, browser.send("PUT", "/api/data", bRecords, Map.of("X-Monetra-User", "tab-b@test.dev")).statusCode());

        /* The first tab still thinks it is A. Its request is refused before anything is read or written. */
        Map<String, String> asA = Map.of("X-Monetra-User", "tab-a@test.dev");

        HttpResponse<String> read = browser.send("GET", "/api/data", null, asA);

        assertEquals(409, read.statusCode(), read.body());
        assertTrue(read.body().contains("\"accountChanged\":true"), read.body());

        HttpResponse<String> write = browser.send("PUT", "/api/data",
                "{\"limits\":{\"daily\":1},\"expenses\":[{\"id\":9,\"name\":\"A's records\",\"amount\":5,\"date\":\"2026-10-17\"}]}", asA);

        assertEquals(409, write.statusCode(), write.body());
        assertEquals(409, browser.send("POST", "/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"9000000177\",\"amountInr\":880}", asA).statusCode());
        assertEquals(409, browser.send("GET", "/api/payments", null, asA).statusCode());

        /* B's records are exactly as B left them, and B has no payment created by A's tab. */
        String kept = browser.get("/api/data").body();

        assertTrue(kept.contains("B's tea") && !kept.contains("A's records"), kept);
        assertEquals("[]", browser.get("/api/payments").body());

        /* B's own tab (the right claim), and a request that makes no claim, work as always. */
        assertEquals(200, browser.send("GET", "/api/data", null, Map.of("X-Monetra-User", "TAB-B@test.dev")).statusCode());
        assertEquals(200, browser.get("/api/data").statusCode());

        /* Sign-in questions are never blocked, so the stale tab can find out who is signed in and reload. */
        HttpResponse<String> who = browser.send("GET", "/api/auth/me", null, asA);

        assertEquals(200, who.statusCode());
        assertTrue(who.body().contains("tab-b@test.dev"), who.body());
    }

    @Test
    void aSessionThatIsReallyGoneStillAnswers401NotTheTabGuard() throws Exception {
        /* Nobody is signed in: the answer is the usual "log in first", so the page knows to ask to sign in. */
        HttpResponse<String> res = new Http(port).send("GET", "/api/data", null, Map.of("X-Monetra-User", "someone@test.dev"));

        assertEquals(401, res.statusCode(), res.body());
    }
}
