package com.monetra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payment confirmation names the person paid. For a number with no name (not on Monetra, not a saved contact)
 * it now says the full number the sender typed, not "Contact 98xxxxxx10". Older payments that were labelled with the
 * mask show the full number too, but only to their own sender, and only when the payment has that number.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-recipient-number",
                "spring.mail.host=localhost",
                "anthropic.api-key=",
                "ai.base-url=",
                "paypal.webhook-id=WH-TEST",
                "monetra.score.daily-job=false"
        })
class RecipientNumberFlowTest {

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

    @Autowired
    ContactRepository contacts;

    @Autowired
    PaymentRepository payments;

    @Autowired
    NotificationRepository notifications;

    @AfterAll
    static void stop() {
        PAYPAL.stop();
    }

    private UserAccount user(String email, String phone, String name) {
        return users.save(new UserAccount(email, new BCryptPasswordEncoder().encode("password-1"), phone, name));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void aNumberWithNoNameIsShownInFullInTheConfirmationHistoryAndNotification() throws Exception {
        user("full1@test.dev", "9000000151", "Full Sender");

        Http sender = Http.login(port, "full1@test.dev", "password-1");

        /* 880 rupees at the default 88 per dollar is exactly 10.00 USD. */
        HttpResponse<String> order = sender.post("/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"9876543210\",\"amountInr\":880,\"description\":\"Snacks\"}");

        assertEquals(200, order.statusCode(), order.body());
        assertTrue(order.body().contains("Contact 98765 43210"), order.body());
        assertFalse(order.body().contains("xxxxxx"), order.body());

        String orderId = Http.field(order.body(), "orderId");

        HttpResponse<String> done = sender.post("/api/payments/capture/" + orderId, "{}");

        assertEquals(200, done.statusCode(), done.body());
        assertTrue(done.body().contains("\"status\":\"COMPLETED\""), done.body());
        assertTrue(done.body().contains("\"recipientPhone\":\"9876543210\""), done.body());
        assertTrue(done.body().contains("Contact 98765 43210"), done.body());

        /* Capturing again only returns the stored result: one payment, one notification, nothing sent twice. */
        assertTrue(sender.post("/api/payments/capture/" + orderId, "{}").body().contains("COMPLETED"));

        String list = sender.get("/api/payments").body();

        assertEquals(1, count(list, "\"paymentId\""), list);
        assertEquals(1, count(list, "\"status\":\"COMPLETED\""), list);
        assertFalse(list.contains("xxxxxx"), list);

        String notes = sender.get("/api/notifications").body();

        assertEquals(1, count(notes, "You sent"), notes);
        assertTrue(notes.contains("You sent ₹880.00 to Contact 98765 43210."), notes);
        assertFalse(notes.contains("xxxxxx"), notes);
    }

    @Test
    void aSavedContactNameStillComesFirst() throws Exception {
        UserAccount sender = user("full2@test.dev", "9000000152", "Name Sender");

        Contact mom = new Contact();
        mom.userId = sender.getId();
        mom.name = "Mom";
        mom.phoneKey = "9876543211";

        contacts.save(mom);

        Http http = Http.login(port, "full2@test.dev", "password-1");

        HttpResponse<String> order = http.post("/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"9876543211\",\"amountInr\":880}");

        assertEquals(200, order.statusCode(), order.body());
        assertTrue(order.body().contains("\"recipientName\":\"Mom\""), order.body());
        assertFalse(order.body().contains("98765 43211"), order.body());
    }

    @Test
    void aPersonOnMonetraIsStillShownByTheirName() throws Exception {
        user("full3@test.dev", "9000000153", "Sender Three");
        user("full4@test.dev", "9000000154", "Priya On Monetra");

        Http sender = Http.login(port, "full3@test.dev", "password-1");

        HttpResponse<String> order = sender.post("/api/payments/create-order",
                "{\"method\":\"PHONE\",\"recipientPhone\":\"9000000154\",\"amountInr\":880}");

        assertEquals(200, order.statusCode(), order.body());
        assertTrue(order.body().contains("Priya On Monetra"), order.body());
        assertFalse(order.body().contains("Contact "), order.body());
    }

    @Test
    void olderMaskedPaymentsShowTheFullNumberOnlyToTheirSenderAndOnlyWhenItIsKnown() throws Exception {
        UserAccount sender = user("full5@test.dev", "9000000155", "Old Sender");
        UserAccount other = user("full6@test.dev", "9000000156", "Other Person");

        payments.save(oldPayment("OLD-FULL", sender.getId(), "Contact 98xxxxxx12", "9876543212"));
        payments.save(oldPayment("OLD-NONE", sender.getId(), "Contact 70xxxxxx99", null));

        notifications.save(new Notification(sender.getId(), "PAYMENT_SENT", "You sent ₹5.00 to Contact 98xxxxxx12.", "OLD-FULL"));
        notifications.save(new Notification(sender.getId(), "PAYMENT_SENT", "You sent ₹5.00 to Contact 70xxxxxx99.", "OLD-NONE"));

        /* A notification of someone else that points at this payment must not reveal its number. */
        notifications.save(new Notification(other.getId(), "PAYMENT_SENT", "You sent ₹5.00 to Contact 98xxxxxx12.", "OLD-FULL"));

        Http me = Http.login(port, "full5@test.dev", "password-1");

        String list = me.get("/api/payments").body();

        assertTrue(list.contains("Contact 98765 43212"), list);
        assertFalse(list.contains("Contact 98xxxxxx12"), list);

        /* No full number was stored for this one, so the mask stays and no digits are made up. */
        assertTrue(list.contains("Contact 70xxxxxx99"), list);

        String mine = me.get("/api/notifications").body();

        assertTrue(mine.contains("You sent ₹5.00 to Contact 98765 43212."), mine);
        assertTrue(mine.contains("You sent ₹5.00 to Contact 70xxxxxx99."), mine);

        String theirs = Http.login(port, "full6@test.dev", "password-1").get("/api/notifications").body();

        assertTrue(theirs.contains("Contact 98xxxxxx12"), theirs);
        assertFalse(theirs.contains("98765 43212"), theirs);

        /* What is stored is unchanged: only what is shown differs. */
        assertTrue(notifications.findAll().stream().anyMatch(n -> n.message.contains("Contact 98xxxxxx12")));
        assertEquals("Contact 98xxxxxx12", payments.findById("OLD-FULL").orElseThrow().recipientName);
    }

    private static PaymentRecord oldPayment(String id, Long senderId, String label, String phone) {
        PaymentRecord p = new PaymentRecord();

        p.paymentId = id;
        p.userId = senderId;
        p.method = "PHONE";
        p.status = "COMPLETED";
        p.message = "Payment confirmed by PayPal.";
        p.recipientName = label;
        p.recipientPhone = phone;
        p.recipientKey = phone == null ? null : "P:" + phone;
        p.amountInr = new BigDecimal("5.00");
        p.amountUsd = new BigDecimal("0.06");
        p.createdAt = Instant.now().toString();

        return p;
    }
}
