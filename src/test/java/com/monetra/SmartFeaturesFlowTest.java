package com.monetra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review boxes without repeats, Safe to Spend Today, the Smart Buy Planner and Scam Shield,
 * end to end through the server.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-smart",
				"spring.mail.host=localhost",
				"paypal.client-id=",
				"paypal.client-secret=",
				"anthropic.api-key=",
				"ai.base-url="
		})
class SmartFeaturesFlowTest {

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	@Autowired
	ScamReportRepository scamReports;

	private Http hari;

	private final LocalDate today = LocalDate.now();

	@BeforeEach
	void setUp() throws Exception {
		if (users.findByEmail("hari@test.dev").isEmpty()) {
			users.save(new UserAccount("hari@test.dev", new BCryptPasswordEncoder().encode("password-h"), "9000000071", "Hari Smart"));
		}

		hari = Http.login(port, "hari@test.dev", "password-h");
	}

	/**
	 * ₹30,000 available, ₹1,000 already spent today, an EMI of ₹6,000 in three days, income of ₹40,000
	 * in ten days, essentials of ₹3,000 a month and a laptop goal saving ₹5,000 a month.
	 */
	private String records() {
		return "{\"limits\":{\"daily\":2000},"
				+ "\"expenses\":[{\"id\":1,\"amount\":1000,\"date\":\"" + today + "\"}],"
				+ "\"profile\":{\"balance\":30000,\"balanceAt\":5,\"payday\":" + today.plusDays(10).getDayOfMonth()
				+ ",\"essentials\":3000,\"monthlyIncome\":40000},"
				+ "\"loans\":[{\"id\":11,\"name\":\"Education Loan\",\"amount\":6000,\"due\":\"" + today.plusDays(3)
				+ "\",\"outstanding\":150000,\"frequency\":\"MONTHLY\",\"status\":\"ACTIVE\"}],"
				+ "\"goals\":[{\"id\":21,\"name\":\"Laptop\",\"target\":60000,\"saved\":20000,\"monthly\":5000,"
				+ "\"priority\":\"HIGH\",\"targetDate\":\"" + today.plusMonths(8) + "\"}]}";
	}

	/* ---------------- Each fact once, in numbered red boxes ---------------- */

	@Test
	void withAWarningEverythingIsInTheRedBoxesAndNothingIsSaidTwice() throws Exception {
		/* The user's own case: a daily limit of ₹100, an EMI of ₹99 and no balance given. */
		assertEquals(200, hari.put("/api/data", "{\"limits\":{\"daily\":100},"
				+ "\"loans\":[{\"id\":11,\"name\":\"home\",\"amount\":99,\"due\":\"" + today.plusDays(8) + "\"}]}").statusCode());

		String body = hari.post("/api/guardian/check", "{\"amountInr\":2222}").body();

		assertTrue(body.contains("\"title\":\"DAILY LIMIT EXCEEDED\""), body);
		assertTrue(body.contains("\"action\":\"Pay ₹100.00 or less to stay inside your daily limit.\""), body);

		/* The EMI and the missing balance are one box, with the EMI's amount said once. */
		assertTrue(body.contains("\"title\":\"LOANS AND BILLS\""), body);
		assertTrue(body.contains("due on " + Inr.date(today.plusDays(8))), body);
		assertTrue(body.contains("because your available balance is not set."), body);
		assertEquals(1, body.split("₹99.00", -1).length - 1, body);
		assertFalse(body.contains("times your EMIs"), body);

		/* Nothing is left over to be shown in a second place. */
		assertTrue(body.contains("\"notes\":[]"), body);
		assertTrue(body.contains("\"missing\":[]"), body);
		assertTrue(body.indexOf("LOANS AND BILLS") < body.indexOf("YOUR DECISION"), body);
	}

	/* ---------------- Safe to spend today ---------------- */

	@Test
	void safeToSpendTodayProtectsEmisEssentialsAndSavings() throws Exception {
		assertEquals(200, hari.put("/api/data", records()).statusCode());

		String overview = hari.get("/api/guardian/overview").body();

		/*
		 * Ten days to the next income. Protected: EMI 6,000 + essentials 1,000 (ten days of 3,000 a month)
		 * + laptop saving 1,666.67 (ten days of 5,000 a month) = 8,666.67. The day began with 31,000
		 * (30,000 now + 1,000 spent today), so 22,333.33 is free: 2,233 a day, and 1,233 is left today.
		 */
		assertTrue(overview.contains("\"protected\":8666.67"), overview);
		assertTrue(overview.contains("\"perDay\":2233.00"), overview);
		assertTrue(overview.contains("\"spentToday\":1000.00"), overview);
		assertTrue(overview.contains("\"amount\":1233.00"), overview);
		assertTrue(overview.contains("You can spend up to ₹1,233.00 today"), overview);

		/* Without a balance nothing is guessed. */
		assertEquals(200, hari.put("/api/data", "{\"limits\":{\"daily\":2000}}").statusCode());

		String unknown = hari.get("/api/guardian/overview").body();

		assertTrue(unknown.contains("\"safeToSpend\":{\"known\":false"), unknown);
	}

	/* ---------------- Smart Buy Planner ---------------- */

	@Test
	void theBuyPlannerFindsTheFirstSafeDay() throws Exception {
		assertEquals(200, hari.put("/api/data", records()).statusCode());

		/*
		 * Day by day: 30,000 now, 100 a day of essentials and 166.67 a day of laptop saving, the EMI of 6,000
		 * in three days and 40,000 of income in ten days. The lowest point is the day before the income:
		 * 30,000 - 2,666.67 - 6,000 = 21,333.33.
		 */
		String cheap = hari.post("/api/guardian/buy-plan", "{\"item\":\"Phone\",\"priceInr\":20000}").body();

		assertTrue(cheap.contains("\"buyToday\":true"), cheap);
		assertTrue(cheap.contains("\"lowestAfter\":1333.33"), cheap);

		/* 30,000 is too much before the income, and safe from the day it arrives. */
		String phone = hari.post("/api/guardian/buy-plan", "{\"item\":\"Phone\",\"priceInr\":30000}").body();

		assertTrue(phone.contains("\"buyToday\":false"), phone);
		assertTrue(phone.contains("your money would fall to -₹8,666.67 on " + Inr.date(today.plusDays(9))
				+ ", which is NOT enough"), phone);
		assertTrue(phone.contains("\"safeDate\":\"" + today.plusDays(10) + "\""), phone);
		assertTrue(phone.contains("\"daysToWait\":10"), phone);

		/* 8,666.67 is still needed, over ten days: 867 a day. */
		assertTrue(phone.contains("\"perDay\":867.00"), phone);
		assertTrue(phone.contains("\"perWeek\":6069.00"), phone);
		assertTrue(phone.contains("\"perMonth\":26010.00"), phone);
		assertTrue(phone.contains("keeps your goal savings of ₹5,000.00 a month going"), phone);

		/* A wanted day before the income is not safe; one after it is, and needs less a day. */
		String early = hari.post("/api/guardian/buy-plan",
				"{\"item\":\"Phone\",\"priceInr\":30000,\"wantedDate\":\"" + today.plusDays(5) + "\"}").body();

		assertTrue(early.contains("\"wantedSafe\":false"), early);
		assertTrue(early.contains("\"estimatedDate\":\"" + today.plusDays(10) + "\""), early);

		String later = hari.post("/api/guardian/buy-plan",
				"{\"item\":\"Phone\",\"priceInr\":30000,\"wantedDate\":\"" + today.plusDays(20) + "\"}").body();

		assertTrue(later.contains("\"wantedSafe\":true"), later);
		assertTrue(later.contains("\"perDay\":434.00"), later);

		/* Saving 3,000 a month (100 a day) takes 87 days: later than the first safe day, so that is the plan. */
		String saving = hari.post("/api/guardian/buy-plan",
				"{\"item\":\"Phone\",\"priceInr\":30000,\"savingMonthly\":3000}").body();

		assertTrue(saving.contains("\"estimatedDate\":\"" + today.plusDays(87) + "\""), saving);

		/* Bad requests are refused, and nobody plans without logging in. */
		assertEquals(400, hari.post("/api/guardian/buy-plan", "{\"item\":\"Phone\",\"priceInr\":0}").statusCode());
		assertEquals(400, hari.post("/api/guardian/buy-plan",
				"{\"item\":\"Phone\",\"priceInr\":100,\"wantedDate\":\"" + today.minusDays(1) + "\"}").statusCode());
		assertEquals(401, new Http(port).post("/api/guardian/buy-plan", "{\"priceInr\":100}").statusCode());
	}

	@Test
	void theBuyPlannerSaysWhatItNeedsInsteadOfGuessing() throws Exception {
		assertEquals(200, hari.put("/api/data", "{\"limits\":{\"daily\":2000}}").statusCode());

		String plan = hari.post("/api/guardian/buy-plan", "{\"item\":\"Phone\",\"priceInr\":30000}").body();

		assertTrue(plan.contains("\"ready\":false"), plan);
		assertTrue(plan.contains("your available balance"), plan);
		assertFalse(plan.contains("safeDate"), plan);
	}

	/* ---------------- Check Balance without Setu keys ---------------- */

	@Test
	void checkBalanceSaysWhatIsMissingInsteadOfShowingAnyNumber() throws Exception {
		String status = hari.get("/api/bank").body();

		assertTrue(status.contains("\"configured\":false"), status);
		assertTrue(status.contains("SETU_AA_CLIENT_ID"), status);

		String balance = hari.post("/api/bank/balance", "{}").body();

		assertTrue(balance.contains("\"state\":\"NOT_CONFIGURED\""), balance);
		assertTrue(balance.contains("\"account\":null"), balance);
		assertFalse(balance.contains("\"balance\""), balance);

		java.net.http.HttpResponse<String> link = hari.post("/api/bank/link", "{}");

		assertEquals(503, link.statusCode(), link.body());
		assertTrue(link.body().contains("SETU_AA_PRODUCT_INSTANCE_ID"), link.body());
	}

	/* ---------------- Scam Shield ---------------- */

	@Test
	void scamShieldChecksAMessageAndKeepsItOnlyWithConsent() throws Exception {
		String scam = "{\"message\":\"Dear customer, your electricity will be disconnected tonight. "
				+ "Pay now at http://bit.ly/eb-pay\"}";

		String result = hari.post("/api/guardian/scam/check", scam).body();

		assertTrue(result.contains("\"level\":\"HIGH\""), result);
		assertTrue(result.contains("\"rule\":\"ELECTRICITY\""), result);
		assertTrue(result.contains("bit.ly"), result);

		assertTrue(hari.post("/api/guardian/scam/check", "{\"message\":\"Lunch at 1 tomorrow?\"}").body()
				.contains("\"level\":\"LOW\""));

		assertEquals(400, hari.post("/api/guardian/scam/check", "{\"message\":\"\"}").statusCode());
		assertEquals(401, new Http(port).post("/api/guardian/scam/check", scam).statusCode());

		/* Checking saves nothing. A report keeps the message only when the user agrees. */
		long before = scamReports.count();

		assertEquals(200, hari.post("/api/guardian/scam/report",
				"{\"level\":\"HIGH\",\"userSays\":\"SAFE\",\"rules\":[\"ELECTRICITY\",\"<script>\"],"
						+ "\"shareMessage\":false,\"message\":\"private text\"}").statusCode());

		assertEquals(before + 1, scamReports.count());

		List<ScamReport> all = scamReports.findAll();

		ScamReport kept = all.get(all.size() - 1);

		assertEquals("ELECTRICITY", kept.rules);
		assertNull(kept.message);

		assertTrue(hari.post("/api/guardian/scam/report",
				"{\"level\":\"LOW\",\"userSays\":\"SCAM\",\"shareMessage\":true,\"message\":\"shared text\"}").body()
				.contains("\"messageKept\":true"));

		assertEquals(400, hari.post("/api/guardian/scam/report", "{\"level\":\"HUGE\",\"userSays\":\"SAFE\"}").statusCode());
	}
}
