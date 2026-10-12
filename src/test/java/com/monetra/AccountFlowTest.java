package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP: login session, saved data per user, server-side budget check. */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-flow",
				"spring.mail.host=localhost",
				"paypal.client-id=",
				"paypal.client-secret=",
				"paypal.webhook-id=",
				"anthropic.api-key="
		})
class AccountFlowTest {

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	private HttpResponse<String> send(HttpClient client, String method, String path, String body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json");

		b.method(method, body == null
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body));

		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpClient newClient() {
		return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
	}

	@Test
	void findsRecipientsByNumberAndAsksForConfirmationOnRiskyPayments() throws Exception {
		BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

		users.save(new UserAccount("sender@test.dev", enc.encode("password-s"), "9000000001", "Sam Sender"));
		users.save(new UserAccount("rahul.kumar@test.dev", enc.encode("password-r"), "+91 98765 43210", null));

		HttpClient s = newClient();

		assertEquals(200, send(s, "POST", "/api/auth/login",
				"{\"email\":\"sender@test.dev\",\"password\":\"password-s\"}").statusCode());

		/* /me shows the name and number. */
		String me = send(s, "GET", "/api/auth/me", null).body();
		assertTrue(me.contains("Sam Sender"), me);
		assertTrue(me.contains("9000000001"), me);

		/* A Monetra user is found by number, and the name comes from the server. */
		String found = send(s, "GET", "/api/payments/recipient?phone=98765%2043210", null).body();
		assertTrue(found.contains("\"onMonetra\":true"), found);
		assertTrue(found.contains("Rahul Kumar"), found);

		/* An unknown number is allowed but is not on Monetra. */
		String stranger = send(s, "GET", "/api/payments/recipient?phone=9111111111", null).body();
		assertTrue(stranger.contains("\"onMonetra\":false"), stranger);

		/* You cannot pay yourself. */
		HttpResponse<String> self = send(s, "POST", "/api/payments/risk",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000001\",\"amountInr\":100}");
		assertEquals(400, self.statusCode());

		/* A large first payment to a new recipient asks for confirmation. */
		HttpResponse<String> risky = send(s, "POST", "/api/payments/risk",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9876543210\",\"amountInr\":7000}");
		assertEquals(200, risky.statusCode());
		assertTrue(risky.body().contains("\"flagged\":true"), risky.body());

		HttpResponse<String> calm = send(s, "POST", "/api/payments/risk",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9876543210\",\"amountInr\":300}");
		assertTrue(calm.body().contains("\"flagged\":false"), calm.body());

		/* Bank details are checked and the bank is detected from the IFSC. */
		assertTrue(send(s, "GET", "/api/payments/bank?ifsc=hdfc0001234", null).body().contains("HDFC Bank"));
		assertEquals(400, send(s, "GET", "/api/payments/bank?ifsc=nope", null).statusCode());

		/* My QR is an SVG image. */
		HttpResponse<String> qr = send(s, "GET", "/api/qr/me", null);
		assertEquals(200, qr.statusCode());
		assertTrue(qr.body().startsWith("<svg"));

		/* Notifications start empty, forecast works without data. */
		assertTrue(send(s, "GET", "/api/notifications", null).body().contains("\"unread\":0"));
		assertTrue(send(s, "GET", "/api/guardian/forecast", null).body().contains("\"status\":\"unknown\""));

		/* Without PayPal credentials, creating an order is refused clearly (no record is faked). */
		HttpResponse<String> order = send(s, "POST", "/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9876543210\",\"amountInr\":300}");
		assertEquals(503, order.statusCode());
	}

	@Test
	void privateEndpointsNeedLogin() throws Exception {
		HttpClient anon = newClient();

		assertEquals(401, send(anon, "GET", "/api/data", null).statusCode());
		assertEquals(401, send(anon, "GET", "/api/payments", null).statusCode());
		assertEquals(401, send(anon, "POST", "/api/guardian/check", "{\"amountInr\":10}").statusCode());
		assertEquals(401, send(anon, "GET", "/api/auth/me", null).statusCode());
		assertEquals(401, send(anon, "GET", "/api/notifications", null).statusCode());
		assertEquals(401, send(anon, "GET", "/api/qr/me", null).statusCode());
		assertEquals(401, send(anon, "GET", "/api/payments/recipient?phone=9876543210", null).statusCode());
		assertEquals(401, send(anon, "GET", "/api/guardian/forecast", null).statusCode());
		assertEquals(401, send(anon, "GET", "/api/home/overview", null).statusCode());
		assertEquals(401, send(anon, "GET", "/api/contacts", null).statusCode());
		assertEquals(401, send(anon, "POST", "/api/agent/chat", "{\"message\":\"hi\"}").statusCode());
		assertEquals(401, send(anon, "PUT", "/api/profile/paypal-email", "{\"email\":\"a@b.co\"}").statusCode());

		/* Webhooks are refused until a webhook id is configured. */
		assertEquals(503, send(anon, "POST", "/api/paypal/webhook", "{}").statusCode());
	}

	@Test
	void loginSavesDataAndChecksBudgetPerUser() throws Exception {
		BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

		users.save(new UserAccount("a@test.dev", enc.encode("password-a"), null));
		users.save(new UserAccount("b@test.dev", enc.encode("password-b"), null));

		HttpClient a = newClient();
		HttpClient b = newClient();

		assertEquals(401, send(a, "POST", "/api/auth/login",
				"{\"email\":\"a@test.dev\",\"password\":\"wrong\"}").statusCode());

		assertEquals(200, send(a, "POST", "/api/auth/login",
				"{\"email\":\"a@test.dev\",\"password\":\"password-a\"}").statusCode());

		assertEquals(200, send(b, "POST", "/api/auth/login",
				"{\"email\":\"b@test.dev\",\"password\":\"password-b\"}").statusCode());

		assertTrue(send(a, "GET", "/api/auth/me", null).body().contains("a@test.dev"));

		String today = LocalDate.now().toString();

		String saved = "{\"dailyLimit\":300,\"expenses\":[{\"amount\":100,\"date\":\"" + today + "\"}]}";

		assertEquals(200, send(a, "PUT", "/api/data", saved).statusCode());

		/* User A: 100 spent + 250 payment is over a 300 limit. */
		HttpResponse<String> check = send(a, "POST", "/api/guardian/check", "{\"amountInr\":250}");
		assertEquals(200, check.statusCode());
		assertTrue(check.body().contains("\"level\":\"over\""), check.body());

		/* User B never saved anything, so A's data must not leak. */
		assertEquals("{}", send(b, "GET", "/api/data", null).body());
		assertTrue(send(b, "POST", "/api/guardian/check", "{\"amountInr\":250}").body()
				.contains("\"level\":\"unknown\""));

		/* Data survives in the database, not just the session. */
		assertTrue(send(a, "GET", "/api/data", null).body().contains("\"dailyLimit\":300"));

		assertEquals(200, send(a, "POST", "/api/auth/logout", null).statusCode());
		assertEquals(401, send(a, "GET", "/api/data", null).statusCode());
	}
}
