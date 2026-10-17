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

	@Test
	void phoneContactsCanBeAddedSearchedAndRemoved() throws Exception {
		BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

		users.save(new UserAccount("omar@test.dev", enc.encode("password-o"), "9000000081", "Omar Owner"));
		users.save(new UserAccount("lakshmi@test.dev", enc.encode("password-l"), "9000000082", "Lakshmi Devi"));
		users.save(new UserAccount("hidden@test.dev", enc.encode("password-h"), "9000000083", "Hema Hidden"));

		Http omar = Http.login(port, "omar@test.dev", "password-o");

		assertEquals(401, new Http(port).post("/api/contacts/import", "{\"contacts\":[]}").statusCode());
		assertEquals(400, omar.post("/api/contacts/import", "{}").statusCode());

		assertEquals(200, omar.post("/api/contacts", "{\"phone\":\"9333333333\",\"name\":\"Milk shop\"}").statusCode());

		/* Amma is on Monetra, the plumber is not. The rest is skipped: a landline, no name,
		   the same number twice, your own number and a contact you already saved by hand. */
		String phoneBook = "{\"contacts\":["
				+ "{\"name\":\"Amma\",\"phone\":\"+91 90000 00082\"},"
				+ "{\"name\":\"Ravi Plumber\",\"phone\":\"098765 43210\"},"
				+ "{\"name\":\"Office\",\"phone\":\"080-2345678\"},"
				+ "{\"name\":\"\",\"phone\":\"9111111111\"},"
				+ "{\"name\":\"Ravi again\",\"phone\":\"9876543210\"},"
				+ "{\"name\":\"Me\",\"phone\":\"9000000081\"},"
				+ "{\"name\":\"Dairy\",\"phone\":\"9333333333\"}]}";

		String first = omar.post("/api/contacts/import", phoneBook).body();
		assertEquals("2", Http.number(first, "added"), first);
		assertEquals("5", Http.number(first, "skipped"), first);

		/* Like a phone book: search by your own name for someone, or by part of the number. */
		String amma = omar.get("/api/contacts?q=amma").body();
		assertTrue(amma.contains("\"name\":\"Amma\""), amma);
		assertTrue(amma.contains("\"source\":\"PHONE\""), amma);
		assertTrue(amma.contains("\"monetraName\":\"Lakshmi Devi\""), amma);
		assertTrue(omar.get("/api/contacts?q=lakshmi").body().contains("\"name\":\"Amma\""));
		assertTrue(omar.get("/api/contacts?q=98765").body().contains("Ravi Plumber"));

		/* Adding your phone book does not let you find Monetra users who are not in it. */
		assertEquals("[]", omar.get("/api/contacts?q=Hema").body());

		/* Typing the number shows your own name for someone who is not on Monetra. */
		String who = omar.get("/api/payments/recipient?phone=9876543210").body();
		assertTrue(who.contains("\"name\":\"Ravi Plumber\""), who);

		/* Adding the same phone book again changes nothing, except a name you changed on the phone. */
		String again = omar.post("/api/contacts/import",
				"{\"contacts\":[{\"name\":\"Amma\",\"phone\":\"9000000082\"},{\"name\":\"Ravi P\",\"phone\":\"9876543210\"}]}").body();
		assertEquals("0", Http.number(again, "added"), again);
		assertEquals("1", Http.number(again, "updated"), again);

		/* Removing the phone contacts keeps the one saved by hand. */
		String removed = omar.delete("/api/contacts/imported").body();
		assertEquals("2", Http.number(removed, "removed"), removed);

		String left = omar.get("/api/contacts").body();
		assertTrue(left.contains("Milk shop"), left);
		assertFalse(left.contains("Amma"), left);
	}

	/** The part of the list that belongs to one person: from their name to the end of their entry. */
	private static String entry(String list, String name) {
		int start = list.indexOf("{\"name\":\"" + name + "\"");

		assertTrue(start >= 0, name + " is in the list: " + list);

		return list.substring(start, list.indexOf('}', start) + 1);
	}

	@Test
	void theListMarksTheNumbersYouAlreadyHaveSoTheyCanBeShownInFullAndOthersStayMasked() throws Exception {
		BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

		users.save(new UserAccount("ken@test.dev", enc.encode("password-k"), "9000000091", "Ken Sender"));
		users.save(new UserAccount("lia@test.dev", enc.encode("password-l"), "9000000092", "Lia Paid"));
		users.save(new UserAccount("max@test.dev", enc.encode("password-m"), "9000000093", "Max Payer"));

		Http ken = Http.login(port, "ken@test.dev", "password-k");
		Http max = Http.login(port, "max@test.dev", "password-m");

		/* A contact you saved yourself. */
		assertEquals(200, ken.post("/api/contacts", "{\"phone\":\"9444444444\",\"name\":\"Cafe\"}").statusCode());

		String cafe = entry(ken.get("/api/contacts?q=Cafe").body(), "Cafe");
		assertTrue(cafe.contains("\"saved\":true") && cafe.contains("\"known\":true"), cafe);

		/* Someone you paid: you typed their number, so it is yours to see. */
		assertEquals(200, ken.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000092\",\"amountInr\":440}").statusCode());

		String lia = entry(ken.get("/api/contacts?q=Lia").body(), "Lia Paid");
		assertTrue(lia.contains("\"known\":true") && lia.contains("\"saved\":false"), lia);

		/* Someone who paid you: their number is theirs, so the list marks it as not yours to see in full. */
		assertEquals(200, max.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000091\",\"amountInr\":440}").statusCode());

		String maxOrder = Http.field(max.get("/api/payments").body(), "orderId");
		assertEquals(200, max.post("/api/payments/capture/" + maxOrder, "{}").statusCode());

		String payer = entry(ken.get("/api/contacts?q=Max").body(), "Max Payer");
		assertTrue(payer.contains("\"known\":false"), payer);
		assertTrue(payer.contains("\"maskedPhone\":\"90xxxxxx93\""), payer);

		/* Once you pay them yourself, you have typed their number: it is shown in full from then on. */
		assertEquals(200, ken.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000093\",\"amountInr\":440}").statusCode());

		assertTrue(entry(ken.get("/api/contacts?q=Max").body(), "Max Payer").contains("\"known\":true"));
	}
}
