package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Home Expense Guardian over real HTTP: schedules, paid and unpaid, reports and reminders. */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-home",
				"spring.mail.host=localhost",
				"paypal.client-id=",
				"paypal.client-secret=",
				"anthropic.api-key="
		})
class HomeFlowTest {

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	private static String expense(String name, String amount, String category, String frequency, LocalDate start) {
		return "{\"name\":\"" + name + "\",\"amount\":" + amount + ",\"category\":\"" + category
				+ "\",\"frequency\":\"" + frequency + "\",\"startDate\":\"" + start + "\"}";
	}

	@Test
	void tracksDueTodayOverduePaidAndReminders() throws Exception {
		users.save(new UserAccount("hana@test.dev", new BCryptPasswordEncoder().encode("password-h"), "9000000051", "Hana"));
		users.save(new UserAccount("other@test.dev", new BCryptPasswordEncoder().encode("password-o"), "9000000052", "Other"));

		Http hana = Http.login(port, "hana@test.dev", "password-h");
		Http other = Http.login(port, "other@test.dev", "password-o");

		LocalDate today = LocalDate.now();

		/* Bad input is refused with a clear message. */
		assertEquals(400, hana.post("/api/home/expenses", expense("Rent", "-5", "Rent", "MONTHLY", today)).statusCode());
		assertEquals(400, hana.post("/api/home/expenses", expense("Rent", "8000", "Rent", "SOMETIMES", today)).statusCode());
		assertEquals(400, hana.post("/api/home/expenses", expense("", "8000", "Rent", "MONTHLY", today)).statusCode());

		HttpResponse<String> rent = hana.post("/api/home/expenses", expense("House rent", "8000", "Rent", "MONTHLY", today));
		assertEquals(200, rent.statusCode(), rent.body());

		String rentId = Http.number(rent.body(), "id");

		assertEquals(200, hana.post("/api/home/expenses", expense("Wi-Fi", "799", "Internet", "ONCE", today.minusDays(2))).statusCode());
		assertEquals(200, hana.post("/api/home/expenses", expense("Insurance", "12000", "Insurance", "YEARLY", today.plusDays(20))).statusCode());

		/* Rent is due today, Wi-Fi is overdue, insurance is not due yet. */
		String overview = hana.get("/api/home/overview").body();

		assertTrue(overview.contains("\"dueCount\":1"), overview);
		assertTrue(overview.contains("\"pendingCount\":1"), overview);
		assertTrue(overview.contains("\"overdueCount\":1"), overview);
		assertTrue(overview.contains("\"status\":\"DUE_TODAY\""), overview);
		assertTrue(overview.contains("\"status\":\"OVERDUE\""), overview);
		String todoList = overview.substring(0, overview.indexOf("\"expenses\""));

		assertFalse(todoList.contains("Insurance"), "insurance is more than 14 days away, so it is not in the to-do list");
		assertTrue(overview.substring(overview.indexOf("\"expenses\"")).contains("Insurance"));

		/* Reminders appear in the bell once, even if the list is loaded again. */
		String notes = hana.get("/api/notifications").body();

		assertTrue(notes.contains("House rent of ₹8000.00 is due today."), notes);
		assertTrue(notes.contains("Wi-Fi of ₹799.00 was due on"), notes);

		String again = hana.get("/api/notifications").body();

		assertEquals(count(notes, "is due today."), count(again, "is due today."));
		assertEquals(count(notes, "was due on"), count(again, "was due on"));

		/* Marking a date that is not a due date is refused. */
		assertEquals(400, hana.post("/api/home/expenses/" + rentId + "/paid",
				"{\"dueDate\":\"" + today.plusDays(1) + "\"}").statusCode());

		/* The user says they paid the rent: it is labelled MANUAL, and today's summary updates. */
		HttpResponse<String> paid = hana.post("/api/home/expenses/" + rentId + "/paid",
				"{\"dueDate\":\"" + today + "\"}");

		assertEquals(200, paid.statusCode(), paid.body());
		assertTrue(paid.body().contains("MANUAL"));

		assertEquals(200, hana.post("/api/home/expenses/" + rentId + "/paid",
				"{\"dueDate\":\"" + today + "\"}").statusCode(), "marking twice is harmless");

		String after = hana.get("/api/home/overview").body();

		assertTrue(after.contains("\"paidCount\":1"), after);
		assertTrue(after.contains("\"pendingCount\":0"), after);

		/* The monthly report counts the rent as paid and the Wi-Fi as outstanding if it is in this month. */
		String report = hana.get("/api/home/report").body();

		assertTrue(report.contains("\"category\":\"Rent\""), report);
		assertTrue(report.contains("\"totalPaid\":8000"), report);

		/* The user can undo a payment they marked themselves. */
		assertEquals(200, hana.delete("/api/home/expenses/" + rentId + "/paid?dueDate=" + today).statusCode());
		assertTrue(hana.get("/api/home/overview").body().contains("\"paidCount\":0"));

		/* Another user cannot see or delete these. */
		assertFalse(other.get("/api/home/overview").body().contains("House rent"));
		assertEquals(404, other.delete("/api/home/expenses/" + rentId).statusCode());
		assertEquals(400, other.post("/api/home/expenses/" + rentId + "/paid",
				"{\"dueDate\":\"" + today + "\"}").statusCode());

		/* The owner can delete it. */
		assertEquals(200, hana.delete("/api/home/expenses/" + rentId).statusCode());
		assertFalse(hana.get("/api/home/overview").body().contains("House rent"));
	}

	@Test
	void aBillPaidInHeIsMarkedPaidAndNotCountedTwice() throws Exception {
		users.save(new UserAccount("ravi@test.dev", new BCryptPasswordEncoder().encode("password-r"), "9000000053", "Ravi"));
		users.save(new UserAccount("sita@test.dev", new BCryptPasswordEncoder().encode("password-s"), "9000000054", "Sita"));

		Http ravi = Http.login(port, "ravi@test.dev", "password-r");
		Http sita = Http.login(port, "sita@test.dev", "password-s");

		LocalDate today = LocalDate.now();

		assertEquals(200, ravi.put("/api/data", "{\"limits\":{\"daily\":5000},\"profile\":{\"balance\":3000,\"balanceAt\":5,"
				+ "\"payday\":" + today.plusDays(10).getDayOfMonth() + ",\"monthlyIncome\":40000}}").statusCode());

		HttpResponse<String> power = ravi.post("/api/home/expenses", expense("Electricity", "2000", "Electricity", "MONTHLY", today));
		assertEquals(200, power.statusCode(), power.body());

		String powerId = Http.number(power.body(), "id");
		String link = "\"homeExpenseId\":" + powerId + ",\"homeDueDate\":\"" + today + "\"";

		/* Checking any other ₹2,000 payment counts the bill as still to pay; checking the bill's own payment does not. */
		String other = ravi.post("/api/guardian/check", "{\"amountInr\":2000}").body();
		String itself = ravi.post("/api/guardian/check", "{\"amountInr\":2000," + link + "}").body();

		assertTrue(other.contains("Electricity"), other);
		assertFalse(itself.contains("Electricity"), itself);

		/* Cash for someone else's bill, or for a day it is not due, is refused and nothing is recorded. */
		assertEquals(400, sita.post("/api/he/pay", "{\"type\":\"MONTHLY\",\"amountInr\":2000,\"method\":\"CASH\",\"confirmed\":true,"
				+ link + "}").statusCode());
		assertEquals(400, ravi.post("/api/he/pay", "{\"type\":\"MONTHLY\",\"amountInr\":2000,\"method\":\"CASH\",\"confirmed\":true,"
				+ "\"homeExpenseId\":" + powerId + ",\"homeDueDate\":\"" + today.plusDays(1) + "\"}").statusCode());
		assertEquals("[]", ravi.get("/api/he/history").body());

		/* Paid in cash through HE: the bill is marked paid, as the user's own word. */
		HttpResponse<String> cash = ravi.post("/api/he/pay", "{\"type\":\"MONTHLY\",\"amountInr\":2000,\"description\":\"Electricity\","
				+ "\"method\":\"CASH\",\"confirmed\":true,\"requestId\":\"bill-1\"," + link + "}");

		assertEquals(200, cash.statusCode(), cash.body());

		String overview = ravi.get("/api/home/overview").body();

		assertTrue(overview.contains("\"paidCount\":1"), overview);
		assertTrue(overview.contains("\"pendingCount\":0"), overview);
		assertTrue(overview.contains("\"due\":[]"), overview);

		String report = ravi.get("/api/home/report?month=" + java.time.YearMonth.now()).body();

		assertTrue(report.contains("\"paidSource\":\"MANUAL\""), report);

		/* The Guardian no longer counts it. */
		assertFalse(ravi.post("/api/guardian/check", "{\"amountInr\":2000}").body().contains("Electricity"));
	}

	private static int count(String text, String part) {
		return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
	}
}
