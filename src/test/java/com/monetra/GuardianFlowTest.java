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
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Financial Guardian end to end: one payment checked against the daily limit, the loans and
 * the savings goals together; the dashboard figures; notifications; and home expenses (HE).
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-guardian",
				"spring.mail.host=localhost",
				"anthropic.api-key=",
				"ai.base-url="
		})
class GuardianFlowTest {

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

	private Http gita;
	private Http other;

	private final LocalDate today = LocalDate.now();

	@BeforeEach
	void setUp() throws Exception {
		if (users.findByEmail("gita@test.dev").isEmpty()) {
			BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

			users.save(new UserAccount("gita@test.dev", enc.encode("password-g"), "9000000061", "Gita Guardian"));
			users.save(new UserAccount("amma@test.dev", enc.encode("password-a"), "9000000062", "AMMA"));
			users.save(new UserAccount("other@test.dev", enc.encode("password-o"), "9000000063", "Other Person"));
		}

		gita = Http.login(port, "gita@test.dev", "password-g");
		other = Http.login(port, "other@test.dev", "password-o");
	}

	/**
	 * A phone for ₹20,000: daily limit ₹2,000 with ₹1,000 spent, ₹30,000 available, an EMI of ₹6,000
	 * in three days, essentials before the next income in ten days, and a laptop goal.
	 */
	private String records(int essentialsAMonth) {
		return "{\"limits\":{\"daily\":2000},"
				+ "\"expenses\":[{\"id\":1,\"amount\":1000,\"date\":\"" + today + "\"}],"
				+ "\"profile\":{\"balance\":30000,\"balanceAt\":5,\"payday\":" + today.plusDays(10).getDayOfMonth()
				+ ",\"essentials\":" + essentialsAMonth + ",\"monthlyIncome\":40000},"
				+ "\"loans\":[{\"id\":11,\"name\":\"Education Loan\",\"amount\":6000,\"due\":\"" + today.plusDays(3)
				+ "\",\"outstanding\":150000,\"frequency\":\"MONTHLY\",\"status\":\"ACTIVE\"}],"
				+ "\"goals\":[{\"id\":21,\"name\":\"Laptop\",\"target\":60000,\"saved\":20000,\"monthly\":5000,"
				+ "\"priority\":\"HIGH\",\"targetDate\":\"" + today.plusMonths(8) + "\"}]}";
	}

	/* ---------------- Acceptance test 5: one payment, every consequence ---------------- */

	@Test
	void onePaymentIsCheckedAgainstTheLimitTheLoansAndTheGoalTogether() throws Exception {
		assertEquals(200, gita.put("/api/data", records(15000)).statusCode());

		String body = gita.post("/api/guardian/check",
				"{\"amountInr\":20000,\"method\":\"PHONE\",\"recipientPhone\":\"9000000062\"}").body();

		/* Daily: 1,000 already spent of 2,000, so 20,000 is over by 19,000 and ten times the limit. */
		assertTrue(body.contains("\"title\":\"DAILY LIMIT EXCEEDED\""), body);
		assertTrue(body.contains("Your ₹2,000.00 daily limit is exceeded by ₹19,000.00."), body);
		assertTrue(body.contains("₹20,000.00 to AMMA is approximately 10 times your daily limit."), body);

		/* Loans: 30,000 - 20,000 - EMI 6,000 - essentials 5,000 (ten days of 15,000 a month) = -1,000. */
		assertTrue(body.contains("\"title\":\"LOAN REPAYMENT RISK\""), body);
		assertTrue(body.contains("your EMIs of ₹6,000.00 and essential expenses of ₹5,000.00"), body);
		assertTrue(body.contains("your projected balance would be -₹1,000.00. That is NOT enough"), body);

		/* Savings: if the money came from the laptop savings, 40,000 still needed becomes 60,000. */
		assertTrue(body.contains("\"title\":\"SAVINGS GOAL IMPACT\""), body);
		assertTrue(body.contains("the amount you still need rises from ₹40,000.00 to ₹60,000.00"), body);
		assertTrue(body.contains("that is about 4 more months of saving"), body);

		assertTrue(body.contains("\"title\":\"YOUR DECISION\""), body);
		assertTrue(body.contains("\"overallLabel\":\"HIGH FINANCIAL RISK\""), body);

		/* Alternatives are offered, never taken. */
		assertTrue(body.contains("Pay ₹1,000.00 or less to stay inside your daily limit."), body);

		/* The older fields the review screen already uses are still there. */
		assertTrue(body.contains("\"level\":\"over\""), body);
	}

	@Test
	void aPaymentThatFitsShowsNoWarningCardsAtAll() throws Exception {
		assertEquals(200, gita.put("/api/data", records(3000)).statusCode());

		String body = gita.post("/api/guardian/check", "{\"amountInr\":200}").body();

		assertTrue(body.contains("\"cards\":[]"), body);
		assertTrue(body.contains("\"overallLabel\":\"LOW FINANCIAL RISK\""), body);

		/* What is fine is said plainly, with the real figures: 30,000 - 200 - 6,000 - 1,000. */
		assertTrue(body.contains("about ₹22,800.00 is projected to be left"), body);
	}

	@Test
	void withoutABalanceNothingIsInventedAndTheUserIsToldWhatIsMissing() throws Exception {
		assertEquals(200, gita.put("/api/data", "{\"limits\":{\"daily\":2000},"
				+ "\"loans\":[{\"id\":11,\"name\":\"Education Loan\",\"amount\":6000,\"due\":\"" + today.plusDays(3) + "\"}]}").statusCode());

		String body = gita.post("/api/guardian/check", "{\"amountInr\":500}").body();

		assertFalse(body.contains("LOAN REPAYMENT RISK"), body);
		assertTrue(body.contains("because your available balance is not set"), body);
	}

	/* ---------------- Dashboard, persistence and security (acceptance tests 7 and 8) ---------------- */

	@Test
	void theDashboardFiguresComeFromTheSavedRecordsAndStayPrivate() throws Exception {
		assertEquals(200, gita.put("/api/data", records(3000)).statusCode());

		/* The records survive a new login. */
		Http again = Http.login(port, "gita@test.dev", "password-g");

		assertTrue(again.get("/api/data").body().contains("Education Loan"));

		String overview = again.get("/api/guardian/overview").body();

		assertTrue(overview.contains("\"totalOutstanding\":150000.00"), overview);
		assertTrue(overview.contains("\"monthlyEmi\":6000.00"), overview);
		assertTrue(overview.contains("\"name\":\"Education Loan\""), overview);
		assertTrue(overview.contains("\"requiredMonthly\":5000.00"), overview);
		assertTrue(overview.contains("\"statusLabel\":\"On track\""), overview);

		/* Next month: 40,000 income - 6,000 EMI - 3,000 essentials - 5,000 planned saving = 26,000. */
		assertTrue(overview.contains("\"projectedRemaining\":26000.00"), overview);

		/* Another user sees none of it, and nobody sees anything without logging in. */
		Http amma = Http.login(port, "amma@test.dev", "password-a");

		String theirs = amma.get("/api/guardian/overview").body();

		assertFalse(theirs.contains("Education Loan"), theirs);
		assertTrue(theirs.contains("\"count\":0"), theirs);
		assertEquals("{}", amma.get("/api/data").body());

		String theirCheck = amma.post("/api/guardian/check", "{\"amountInr\":20000}").body();

		assertFalse(theirCheck.contains("Laptop"), theirCheck);
		assertFalse(theirCheck.contains("LOAN REPAYMENT RISK"), theirCheck);

		assertEquals(401, new Http(port).get("/api/guardian/overview").statusCode());
		assertEquals(401, new Http(port).get("/api/he/history").statusCode());
	}

	@Test
	void badLoanAndGoalFiguresAreRefusedByTheServer() throws Exception {
		assertEquals(400, gita.put("/api/data", "{\"loans\":[{\"name\":\"Bad\",\"amount\":-5,\"due\":\"2026-11-05\"}]}").statusCode());
		assertEquals(400, gita.put("/api/data", "{\"loans\":[{\"name\":\"Bad\",\"amount\":500,\"due\":\"not-a-date\"}]}").statusCode());
		assertEquals(400, gita.put("/api/data", "{\"goals\":[{\"name\":\"Bad\",\"target\":0,\"saved\":0}]}").statusCode());
		assertEquals(400, gita.put("/api/data", "{\"profile\":{\"payday\":45}}").statusCode());

		/* Records from before the upgrade (a loan with just a name, an EMI and a date) are still accepted. */
		assertEquals(200, gita.put("/api/data", "{\"loans\":[{\"name\":\"Old\",\"amount\":500,\"due\":\"2026-11-05\",\"notes\":\"\"}],"
				+ "\"goals\":[{\"name\":\"Old goal\",\"target\":1000,\"saved\":0}]}").statusCode());
	}

	/* ---------------- Notifications ---------------- */

	@Test
	void guardianNotificationsAreCreatedOnceEach() throws Exception {
		assertEquals(200, other.put("/api/data", "{\"limits\":{\"daily\":1000},"
				+ "\"expenses\":[{\"id\":1,\"amount\":1200,\"date\":\"" + today + "\"}],"
				+ "\"loans\":[{\"id\":31,\"name\":\"Bike EMI\",\"amount\":2500,\"due\":\"" + today + "\"}],"
				+ "\"goals\":[{\"id\":41,\"name\":\"Fees\",\"target\":5000,\"saved\":5000}],"
				+ "\"he\":{\"reminders\":{\"daily\":{\"on\":true,\"time\":\"00:00\"}}}}").statusCode());

		String first = other.get("/api/notifications").body();

		assertTrue(first.contains("You are ₹200.00 over today's spending limit of ₹1,000.00."), first);
		assertTrue(first.contains("Your EMI \\\"Bike EMI\\\" of ₹2,500.00 is due today."), first);
		assertTrue(first.contains("You reached your savings goal \\\"Fees\\\"."), first);
		assertTrue(first.contains("HE reminder: time to pay today's home expenses."), first);

		/* Asking again does not send them again. */
		String second = other.get("/api/notifications").body();

		assertEquals(1, second.split("is due today", -1).length - 1, second);
		assertEquals(1, second.split("HE reminder", -1).length - 1, second);

		/* A user can switch an area off. */
		assertEquals(200, gita.put("/api/data", "{\"limits\":{\"daily\":1000},"
				+ "\"expenses\":[{\"id\":1,\"amount\":1200,\"date\":\"" + today + "\"}],"
				+ "\"profile\":{\"notify\":{\"daily\":false}}}").statusCode());

		assertFalse(gita.get("/api/notifications").body().contains("over today's spending limit"));
	}

	/* ---------------- HE: home expenses ---------------- */

	@Test
	void theServerSaysWhatEachPaymentMethodReallyDoes() throws Exception {
		String methods = gita.get("/api/he/methods").body();

		/* Scan and mobile number go through PayPal. Cash is only recorded. There is no card or bank transfer option. */
		assertTrue(methods.contains("\"id\":\"SCAN\",\"label\":\"Scan & Pay\",\"kind\":\"PAYPAL\",\"available\":true"), methods);
		assertTrue(methods.contains("\"id\":\"MOBILE\",\"label\":\"Mobile Number\",\"kind\":\"PAYPAL\",\"available\":true"), methods);
		assertFalse(methods.contains("CARD"), methods);
		assertTrue(methods.contains("\"id\":\"MYQR\",\"label\":\"My Payment QR\",\"kind\":\"RECEIVE\""), methods);
		assertTrue(methods.contains("\"id\":\"CASH\",\"label\":\"Cash\",\"kind\":\"RECORD\""), methods);
		assertFalse(methods.contains("BANK"), methods);

		assertEquals(401, new Http(port).get("/api/he/methods").statusCode());
	}

	@Test
	void cashIsRecordedOnlyWhenTheUserConfirmsIt() throws Exception {
		Http amma = Http.login(port, "amma@test.dev", "password-a");

		assertEquals("[]", amma.get("/api/he/history").body());

		assertEquals(400, amma.post("/api/he/pay", "{\"type\":\"WEEKLY\",\"amountInr\":60,\"method\":\"CASH\",\"confirmed\":true}").statusCode());
		assertEquals(400, amma.post("/api/he/pay", "{\"type\":\"DAILY\",\"amountInr\":0,\"method\":\"CASH\",\"confirmed\":true}").statusCode());

		/* A mobile payment cannot be "recorded": it is only real when PayPal confirms it. */
		assertEquals(400, amma.post("/api/he/pay", "{\"type\":\"DAILY\",\"amountInr\":60,\"method\":\"MOBILE\",\"confirmed\":true}").statusCode());

		/* Not confirmed, not recorded. */
		assertEquals(400, amma.post("/api/he/pay", "{\"type\":\"DAILY\",\"amountInr\":60,\"method\":\"CASH\"}").statusCode());
		assertEquals("[]", amma.get("/api/he/history").body());

		String milk = "{\"type\":\"DAILY\",\"amountInr\":60,\"description\":\"Milk\",\"method\":\"CASH\","
				+ "\"recipient\":\"Milkman\",\"confirmed\":true,\"requestId\":\"press-1\"}";

		assertEquals(200, amma.post("/api/he/pay", milk).statusCode());

		/* Pressing the button twice records it once. */
		assertEquals(200, amma.post("/api/he/pay", milk).statusCode());
		assertEquals(1, amma.get("/api/he/history").body().split("\"description\":\"Milk\"", -1).length - 1);

		HttpResponse<String> history = amma.get("/api/he/history");

		assertTrue(history.body().contains("\"status\":\"PAID\""), history.body());
		assertTrue(history.body().contains("\"transactionId\":\"HE-"), history.body());

		/* Bank transfers are no longer an HE method. */
		assertEquals(400, amma.post("/api/he/pay", "{\"type\":\"MONTHLY\",\"amountInr\":8000,\"method\":\"BANK\",\"transferType\":\"IMPS\","
				+ "\"recipient\":\"Landlord\",\"confirmed\":true}").statusCode());

		/* Nobody else sees it. */
		assertFalse(other.get("/api/he/history").body().contains("Milkman"));
	}

	@Test
	void scanAndMobilePaymentsGoThroughPaypalAndAreVerified() throws Exception {
		assertEquals("[]", gita.get("/api/he/history").body());

		/* Mobile number: an ordinary PayPal order to the person with that number. */
		HttpResponse<String> rent = gita.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000062\",\"amountInr\":4400,\"description\":\"Rent\","
						+ "\"heType\":\"MONTHLY\",\"heMethod\":\"MOBILE\"}");

		assertEquals(200, rent.statusCode(), rent.body());
		assertFalse(PAYPAL.orderRequests.get(PAYPAL.orderRequests.size() - 1).contains("landing_page"));

		String history = gita.get("/api/he/history").body();

		assertTrue(history.contains("\"description\":\"Rent\""), history);
		assertTrue(history.contains("\"type\":\"MONTHLY\""), history);
		assertTrue(history.contains("\"method\":\"MOBILE\""), history);
		assertTrue(history.contains("\"recipient\":\"AMMA\""), history);

		/* Not successful until PayPal says so. */
		assertTrue(history.contains("\"status\":\"AWAITING_APPROVAL\""), history);

		/* The ordinary payments list does not show the home expense, and the HE history is private. */
		assertEquals("[]", gita.get("/api/payments").body());
		assertFalse(other.get("/api/he/history").body().contains("Rent"));

		/* It is still a real payment: capturing it completes it, and the receiver is told. */
		String orderId = Http.field(history, "orderId");

		assertEquals(200, gita.post("/api/payments/capture/" + orderId, "{}").statusCode());

		String after = gita.get("/api/he/history").body();

		assertTrue(after.contains("\"status\":\"COMPLETED\""), after);
		assertTrue(after.contains("\"transactionId\":\"CAP-" + orderId + "\""), after);

		Http amma = Http.login(port, "amma@test.dev", "password-a");

		assertTrue(amma.get("/api/payments").body().contains("Gita Guardian"));

		/* A number that is not on Monetra can still be paid through PayPal, the same as on the Pay screen. */
		HttpResponse<String> unknown = gita.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9111111111\",\"amountInr\":300,\"description\":\"Milk\","
						+ "\"heType\":\"DAILY\",\"heMethod\":\"MOBILE\",\"confirmRisk\":true}");

		assertEquals(200, unknown.statusCode(), unknown.body());
		assertTrue(gita.get("/api/he/history").body().contains("\"description\":\"Milk\""));

		/* There is no card payment: without a mobile number nothing is created. */
		assertEquals(400, gita.post("/api/payments/create-order",
				"{\"amountInr\":300,\"heType\":\"DAILY\",\"heMethod\":\"CARD\"}").statusCode());

		/* An ordinary payment (no HE type) still opens PayPal the usual way. */
		assertEquals(200, gita.post("/api/payments/create-order",
				"{\"method\":\"PHONE\",\"recipientPhone\":\"9000000062\",\"amountInr\":440,\"confirmRisk\":true}").statusCode());
		assertFalse(PAYPAL.orderRequests.get(PAYPAL.orderRequests.size() - 1).contains("landing_page"));
	}
}
