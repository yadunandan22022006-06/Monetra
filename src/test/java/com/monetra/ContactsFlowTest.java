package com.monetra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contacts search on the Send Money screen: saved people and people you dealt with, never everyone. */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-contacts",
				"spring.mail.host=localhost",
				"anthropic.api-key="
		})
class ContactsFlowTest {

	private static final FakePaypal PAYPAL = new FakePaypal();

	@DynamicPropertySource
	static void paypalProperties(DynamicPropertyRegistry registry) {
		registry.add("paypal.base-url", PAYPAL::baseUrl);
		registry.add("paypal.client-id", () -> "test-client");
		registry.add("paypal.client-secret", () -> "test-secret");
	}

	@AfterAll
	static void stop() {
		PAYPAL.stop();
	}

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	@Test
	void contactsCoverSavedAndRecentPeopleOnly() throws Exception {
		BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

		users.save(new UserAccount("carl@test.dev", enc.encode("password-c"), "9000000071", "Carl Owner"));
		users.save(new UserAccount("dina@test.dev", enc.encode("password-d"), "9000000072", "Dina Friend"));
		users.save(new UserAccount("stranger@test.dev", enc.encode("password-x"), "9000000073", "Stella Stranger"));
		users.save(new UserAccount("rita@test.dev", enc.encode("password-r"), "9000000074", "Rita Recent"));

		Http carl = Http.login(port, "carl@test.dev", "password-c");

		assertEquals("[]", carl.get("/api/contacts").body());

		/* A Monetra user is saved under their real name. */
		assertEquals(200, carl.post("/api/contacts", "{\"phone\":\"+91 90000 00072\",\"name\":\"ignored\"}").statusCode());

		/* Someone not on Monetra needs a label. You cannot save yourself. */
		assertEquals(400, carl.post("/api/contacts", "{\"phone\":\"9222222222\"}").statusCode());
		assertEquals(400, carl.post("/api/contacts", "{\"phone\":\"9000000071\",\"name\":\"me\"}").statusCode());
		assertEquals(200, carl.post("/api/contacts", "{\"phone\":\"9222222222\",\"name\":\"Milk shop\"}").statusCode());

		String all = carl.get("/api/contacts").body();
		assertTrue(all.contains("Dina Friend"), all);
		assertTrue(all.contains("Milk shop"), all);

		/* Search works by name or by part of a number. */
		assertTrue(carl.get("/api/contacts?q=din").body().contains("Dina Friend"));
		assertFalse(carl.get("/api/contacts?q=din").body().contains("Milk shop"));
		assertTrue(carl.get("/api/contacts?q=2222").body().contains("Milk shop"));

		/* A user you never dealt with cannot be found, even by exact name or number. */
		assertEquals("[]", carl.get("/api/contacts?q=Stella").body());
		assertEquals("[]", carl.get("/api/contacts?q=9000000073").body());

		/* Someone you pay shows up as a recent contact. */
		assertEquals(200, carl.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000074\",\"amountInr\":440}").statusCode());

		String recent = carl.get("/api/contacts?q=Rita").body();
		assertTrue(recent.contains("Rita Recent"), recent);
		assertTrue(recent.contains("RECENT"), recent);

		/* The person who received money can find the sender too. */
		Http rita = Http.login(port, "rita@test.dev", "password-r");
		String orderId = Http.field(carl.get("/api/payments").body(), "orderId");
		assertEquals(200, carl.post("/api/payments/capture/" + orderId, "{}").statusCode());
		assertTrue(rita.get("/api/contacts?q=Carl").body().contains("Carl Owner"));

		/* A saved contact can be removed. */
		assertEquals(200, carl.delete("/api/contacts/9222222222").statusCode());
		assertFalse(carl.get("/api/contacts").body().contains("Milk shop"));
		assertEquals(404, carl.delete("/api/contacts/9222222222").statusCode());
	}
}
