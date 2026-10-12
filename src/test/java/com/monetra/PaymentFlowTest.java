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
 * The whole payment journey against a small fake PayPal server running on this machine:
 * create order, capture, confirmed history for both people, notifications, payouts to the
 * receiver's PayPal email, webhooks, and the safety rules.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-pay",
				"spring.mail.host=localhost",
				"anthropic.api-key=",
				"paypal.webhook-id=WH-TEST"
		})
class PaymentFlowTest {

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

	private static final Map<String, String> GOOD = Map.of("PAYPAL-TRANSMISSION-ID", "good");
	private static final Map<String, String> BAD = Map.of("PAYPAL-TRANSMISSION-ID", "bad");

	private void user(String email, String password, String phone, String name) {
		users.save(new UserAccount(email, new BCryptPasswordEncoder().encode(password), phone, name));
	}

	@Test
	void paymentFromOnePersonAppearsForBothWithNotificationsAndPayout() throws Exception {
		user("asha@test.dev", "password-a", "9000000011", "Asha Sender");
		user("ravi@test.dev", "password-r", "9000000012", "Ravi Receiver");
		user("mallory@test.dev", "password-m", "9000000013", "Mallory");

		Http asha = Http.login(port, "asha@test.dev", "password-a");
		Http ravi = Http.login(port, "ravi@test.dev", "password-r");
		Http mallory = Http.login(port, "mallory@test.dev", "password-m");

		/* 880 rupees at the default 88 per dollar is exactly 10.00 USD. */
		HttpResponse<String> order = asha.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000012\",\"amountInr\":880,\"description\":\"Lunch\"}");

		assertEquals(200, order.statusCode(), order.body());
		assertTrue(order.body().contains("Ravi Receiver"), order.body());

		String orderId = Http.field(order.body(), "orderId");
		String paymentId = Http.field(order.body(), "paymentId");

		assertTrue(orderId.startsWith("ORDER"));

		/* Before approval nothing is shown to the receiver. */
		assertEquals("[]", ravi.get("/api/payments").body());

		/* Someone else cannot capture it. */
		assertEquals(403, mallory.post("/api/payments/capture/" + orderId, "{}").statusCode());

		/* The sender captures after approving on PayPal. */
		HttpResponse<String> captured = asha.post("/api/payments/capture/" + orderId, "{}");

		assertEquals(200, captured.statusCode(), captured.body());
		assertTrue(captured.body().contains("\"status\":\"COMPLETED\""), captured.body());
		assertTrue(captured.body().contains("\"direction\":\"SENT\""), captured.body());

		/* Capturing twice returns the stored result and does not notify twice. */
		assertTrue(asha.post("/api/payments/capture/" + orderId, "{}").body().contains("COMPLETED"));

		/* The sender sees it as sent. */
		String ashaList = asha.get("/api/payments").body();
		assertTrue(ashaList.contains("\"direction\":\"SENT\""), ashaList);
		assertTrue(ashaList.contains("Ravi Receiver"), ashaList);

		/* The receiver sees it as received from Asha. No PayPal order id is shown to them. */
		String raviList = ravi.get("/api/payments").body();
		assertTrue(raviList.contains("\"direction\":\"RECEIVED\""), raviList);
		assertTrue(raviList.contains("Asha Sender"), raviList);
		assertTrue(raviList.contains(paymentId), raviList);
		assertFalse(raviList.contains("orderId"), "the receiver must not see PayPal order ids");

		/* Ravi has no PayPal email yet: the payout waits, and he is told. */
		assertTrue(raviList.contains("\"payoutStatus\":\"WAITING_EMAIL\""), raviList);

		String raviNotes = ravi.get("/api/notifications").body();
		assertTrue(raviNotes.contains("\"unread\":2"), raviNotes);
		assertTrue(raviNotes.contains("You received"), raviNotes);
		assertTrue(raviNotes.contains("Add your PayPal email"), raviNotes);
		assertTrue(raviNotes.contains("\"createdAt\":\"20"), "dates must be readable text: " + raviNotes);

		assertEquals(200, ravi.post("/api/notifications/read", "{}").statusCode());
		assertTrue(ravi.get("/api/notifications").body().contains("\"unread\":0"));

		/* A bad PayPal email is refused. */
		assertEquals(400, ravi.put("/api/profile/paypal-email", "{\"email\":\"nope\"}").statusCode());

		/* Ravi adds his PayPal email: the waiting payout is sent to it. */
		assertEquals(200, ravi.put("/api/profile/paypal-email", "{\"email\":\"Ravi.Buyer@Example.com\"}").statusCode());

		assertEquals(java.util.List.of("ravi.buyer@example.com"), PAYPAL.payoutReceivers);
		assertTrue(ravi.get("/api/payments").body().contains("\"payoutStatus\":\"PENDING\""));

		/* PayPal later says the payout succeeded (webhook), and Ravi hears about it once. */
		String itemEvent = "{\"event_type\":\"PAYMENT.PAYOUTS-ITEM.SUCCEEDED\",\"resource\":{\"sender_item_id\":\"" + paymentId + "\"}}";

		assertEquals(200, ravi.send("POST", "/api/paypal/webhook", itemEvent, GOOD).statusCode());
		assertEquals(200, ravi.send("POST", "/api/paypal/webhook", itemEvent, GOOD).statusCode());

		assertTrue(ravi.get("/api/payments").body().contains("\"payoutStatus\":\"SENT\""));

		String notes = ravi.get("/api/notifications").body();
		assertTrue(notes.contains("\"unread\":1"), notes);
		assertTrue(notes.contains("payout of"), notes);

		/* A stranger sees nothing. */
		assertEquals("[]", mallory.get("/api/payments").body());

		/* The sender was notified too. */
		assertTrue(asha.get("/api/notifications").body().contains("You sent"));
	}

	@Test
	void webhookCompletesAPaymentEvenIfTheBrowserNeverReturns() throws Exception {
		user("wendy@test.dev", "password-w", "9000000041", "Wendy");
		user("wes@test.dev", "password-e", "9000000042", "Wes");

		Http wendy = Http.login(port, "wendy@test.dev", "password-w");
		Http wes = Http.login(port, "wes@test.dev", "password-e");

		HttpResponse<String> order = wendy.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000042\",\"amountInr\":1760}");

		String orderId = Http.field(order.body(), "orderId");

		String event = "{\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"CAP-HOOK\","
				+ "\"amount\":{\"currency_code\":\"USD\",\"value\":\"20.00\"},"
				+ "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + orderId + "\"}}}}";

		/* A webhook that PayPal's signature check rejects changes nothing. */
		assertEquals(401, wendy.send("POST", "/api/paypal/webhook", event, BAD).statusCode());
		assertEquals("[]", wes.get("/api/payments").body());

		/* A genuine one with the wrong amount changes nothing. */
		String wrong = event.replace("20.00", "1.00");
		assertEquals(200, wendy.send("POST", "/api/paypal/webhook", wrong, GOOD).statusCode());
		assertEquals("[]", wes.get("/api/payments").body());

		/* A genuine one with the right amount completes the payment. Sending it again changes nothing more. */
		assertEquals(200, wendy.send("POST", "/api/paypal/webhook", event, GOOD).statusCode());
		assertEquals(200, wendy.send("POST", "/api/paypal/webhook", event, GOOD).statusCode());

		assertTrue(wes.get("/api/payments").body().contains("\"direction\":\"RECEIVED\""));

		String notes = wes.get("/api/notifications").body();
		assertEquals(1, notes.split("You received", -1).length - 1, notes);
	}

	@Test
	void largeFirstPaymentNeedsConfirmationAndIsRecordedAsFlagged() throws Exception {
		user("nina@test.dev", "password-n", "9000000021", "Nina");

		Http nina = Http.login(port, "nina@test.dev", "password-n");

		String request = "{\"method\":\"PHONE\",\"recipientPhone\":\"9111111122\",\"amountInr\":8800%s}";

		HttpResponse<String> held = nina.post("/api/payments/create-order", String.format(request, ""));

		assertEquals(409, held.statusCode(), held.body());
		assertTrue(held.body().contains("needsConfirmation"), held.body());

		HttpResponse<String> confirmed = nina.post("/api/payments/create-order",
				String.format(request, ",\"confirmRisk\":true"));

		assertEquals(200, confirmed.statusCode(), confirmed.body());

		String list = nina.get("/api/payments").body();
		assertTrue(list.contains("riskFlags"), list);
		assertTrue(list.contains("first payment"), list);
	}

	@Test
	void bankTransfersKeepOnlyTheLastFourDigits() throws Exception {
		user("omar@test.dev", "password-o", "9000000031", "Omar");

		Http omar = Http.login(port, "omar@test.dev", "password-o");

		HttpResponse<String> order = omar.post("/api/payments/create-order",
				"{\"method\":\"BANK\",\"ifsc\":\"HDFC0001234\",\"accountNumber\":\"123456789012\",\"amountInr\":880}");

		assertEquals(200, order.statusCode(), order.body());

		String list = omar.get("/api/payments").body();

		assertTrue(list.contains("HDFC Bank"), list);
		assertTrue(list.contains("\"bankLast4\":\"9012\""), list);
		assertFalse(list.contains("123456789012"), "the full account number must never be stored or returned");
	}
}
