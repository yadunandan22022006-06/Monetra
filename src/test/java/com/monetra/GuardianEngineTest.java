package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Guardian's sums: daily budget, loan repayment, next month and savings goals. No AI, no database. */
class GuardianEngineTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

	private final ObjectMapper mapper = new ObjectMapper();

	private final DailyBudgetService dailyBudget = new DailyBudgetService();
	private final LoanRepaymentService loanRepayment = new LoanRepaymentService();
	private final SavingsGoalService savingsGoals = new SavingsGoalService();
	private final FinancialForecastService forecasts = new FinancialForecastService();

	private JsonNode data(String json) throws Exception {
		return mapper.readTree(json);
	}

	private static BigDecimal rupees(String value) {
		return new BigDecimal(value);
	}

	/* ---------------- Acceptance test 1: daily limit ---------------- */

	@Test
	void dailyLimitOf1000With700SpentLeaves300AndA500PaymentIsOverBy200() throws Exception {
		JsonNode d = data("{\"limits\":{\"daily\":1000},\"expenses\":[{\"amount\":700,\"date\":\"2026-10-11\"}]}");

		Map<String, Object> before = dailyBudget.assess(d, BigDecimal.ZERO, TODAY);

		assertEquals(rupees("300.00"), before.get("remaining"));
		assertEquals(70, before.get("percentUsed"));
		assertEquals("LOW", before.get("status"));

		Map<String, Object> paying = dailyBudget.assess(d, rupees("500"), TODAY);

		assertEquals(rupees("200.00"), paying.get("overBy"));
		assertEquals("HIGH", paying.get("status"));
		assertEquals(1, ((List<?>) paying.get("broken")).size());
	}

	@Test
	void anEmiMarkedAsPaidLowersTheBalanceButDoesNotUseUpTheDailyLimit() throws Exception {
		JsonNode d = data("{\"limits\":{\"daily\":1000},\"profile\":{\"balance\":25000,\"balanceAt\":10},"
				+ "\"expenses\":[{\"id\":20,\"amount\":5000,\"date\":\"2026-10-11\",\"source\":\"emi\"},"
				+ "{\"id\":30,\"amount\":200,\"date\":\"2026-10-11\"}]}");

		Map<String, Object> today = dailyBudget.assess(d, BigDecimal.ZERO, TODAY);

		assertEquals(rupees("200.00"), today.get("spentToday"));
		assertEquals("LOW", today.get("status"));

		/* The money still left the account. */
		assertEquals(rupees("19800.00"), forecasts.availableBalance(d));
	}

	@Test
	void withoutADailyLimitTheBudgetIsUnknownNotFine() throws Exception {
		Map<String, Object> out = dailyBudget.assess(data("{}"), rupees("500"), TODAY);

		assertEquals(false, out.get("limitSet"));
		assertEquals("UNKNOWN", out.get("status"));
		assertFalse(out.containsKey("overBy"));
	}

	/* ---------------- Acceptance test 2: loan repayment ---------------- */

	@Test
	void balance25000Minus15000PurchaseEmi6000AndEssentials5000IsMinus1000AndHighRisk() {
		Map<String, Object> out = loanRepayment.assess(
				rupees("25000"), BigDecimal.ZERO, rupees("15000"), rupees("6000"), rupees("5000"), BigDecimal.ZERO, BigDecimal.ZERO);

		assertEquals(rupees("-1000.00"), out.get("projectedBalance"));
		assertEquals("HIGH", out.get("status"));
	}

	@Test
	void aThinBufferIsMediumRiskAndAComfortableOneIsLow() {
		/* 25,000 - 13,000 - 6,000 - 5,000 = 1,000 left: positive, but under a tenth of what the user has. */
		assertEquals("MEDIUM", loanRepayment.assess(
				rupees("25000"), BigDecimal.ZERO, rupees("13000"), rupees("6000"), rupees("5000"), BigDecimal.ZERO, BigDecimal.ZERO).get("status"));

		assertEquals("LOW", loanRepayment.assess(
				rupees("25000"), BigDecimal.ZERO, rupees("2000"), rupees("6000"), rupees("5000"), BigDecimal.ZERO, BigDecimal.ZERO).get("status"));

		/* The user's own emergency buffer is used when they set one. */
		assertEquals("MEDIUM", loanRepayment.assess(
				rupees("25000"), BigDecimal.ZERO, rupees("2000"), rupees("6000"), rupees("5000"), BigDecimal.ZERO, rupees("15000")).get("status"));
	}

	@Test
	void everyEmiIsCountedExactlyOnceIncludingAnOverdueOne() throws Exception {
		JsonNode d = data("{\"loans\":["
				+ "{\"id\":1,\"name\":\"Education Loan\",\"amount\":5000,\"due\":\"2026-11-05\",\"outstanding\":150000},"
				+ "{\"id\":2,\"name\":\"Personal Loan\",\"amount\":3000,\"due\":\"2026-10-01\",\"outstanding\":40000},"
				+ "{\"id\":3,\"name\":\"Old Loan\",\"amount\":9000,\"due\":\"2026-10-20\",\"status\":\"CLOSED\"}]}");

		List<LoanRepaymentService.Loan> loans = loanRepayment.loans(d);

		assertEquals(2, loans.size(), "a closed loan is not counted");
		assertEquals(rupees("8000.00"), loanRepayment.monthlyEmi(loans));
		assertEquals(rupees("190000.00"), loanRepayment.outstanding(loans));

		/* Up to 31 October: the overdue 1 October EMI, and the same loan's next one on 1 November is outside. */
		List<LoanRepaymentService.Installment> october = loanRepayment.schedule(loans, TODAY, LocalDate.of(2026, 10, 31));

		assertEquals(1, october.size());
		assertTrue(october.get(0).overdue());
		assertEquals(rupees("3000.00"), loanRepayment.total(october));

		/* Up to 10 November: overdue 3,000 + 1 November 3,000 + 5 November 5,000. */
		assertEquals(rupees("11000.00"), loanRepayment.total(loanRepayment.schedule(loans, TODAY, LocalDate.of(2026, 11, 10))));
	}

	/* ---------------- Acceptance test 3: next month ---------------- */

	@Test
	void nextMonthIncome20000LessEmis8000Essentials7000Savings2000Leaves3000() {
		Map<String, Object> out = forecasts.nextMonth(
				rupees("20000"), rupees("8000"), rupees("7000"), rupees("2000"), BigDecimal.ZERO, null);

		assertEquals(true, out.get("known"));
		assertEquals(rupees("3000.00"), out.get("projectedRemaining"));

		Map<String, Object> withPurchase = forecasts.nextMonth(
				rupees("20000"), rupees("8000"), rupees("7000"), rupees("2000"), BigDecimal.ZERO, rupees("5000"));

		assertEquals(rupees("-2000.00"), withPurchase.get("projectedWithPurchase"));
	}

	@Test
	void withoutAnIncomeTheForecastIsIncompleteNotInvented() {
		Map<String, Object> out = forecasts.nextMonth(
				BigDecimal.ZERO, rupees("8000"), rupees("7000"), rupees("2000"), BigDecimal.ZERO, null);

		assertEquals(false, out.get("known"));
		assertFalse(out.containsKey("projectedRemaining"));
	}

	@Test
	void theBalanceTheUserTypedMovesWithWhatTheyRecordAfterwards() throws Exception {
		assertNull(forecasts.availableBalance(data("{}")), "no balance was ever given");

		JsonNode d = data("{\"profile\":{\"balance\":25000,\"balanceAt\":1000,\"payday\":1},"
				+ "\"expenses\":[{\"id\":500,\"amount\":900,\"date\":\"2026-10-10\"},{\"id\":2000,\"amount\":400,\"date\":\"2026-10-11\"}],"
				+ "\"income\":[{\"id\":3000,\"amount\":150,\"date\":\"2026-10-11\"}]}");

		/* Only what was recorded after the balance was typed counts: 25,000 - 400 + 150. */
		assertEquals(rupees("24750.00"), forecasts.availableBalance(d));

		assertEquals(LocalDate.of(2026, 11, 1), forecasts.nextPayday(d, TODAY));
		assertNull(forecasts.nextPayday(data("{}"), TODAY));
	}

	/* ---------------- Acceptance test 4: savings goal ---------------- */

	@Test
	void target60000With15000SavedAndNineMonthsLeftNeeds5000AMonth() throws Exception {
		assertEquals(rupees("5000.00"), SavingsGoalService.requiredContribution(rupees("45000"), 9));

		JsonNode d = data("{\"goals\":[{\"id\":7,\"name\":\"Laptop\",\"target\":60000,\"saved\":15000,\"targetDate\":\"2027-07-11\"}]}");

		Map<String, Object> goal = savingsGoals.goals(d, TODAY).get(0);

		assertEquals(rupees("45000.00"), goal.get("remaining"));
		assertEquals(25, goal.get("percent"));
		assertEquals(9L, goal.get("monthsLeft"));
		assertEquals(rupees("5000.00"), goal.get("requiredMonthly"));
		assertEquals("ON_TRACK", goal.get("status"));
	}

	@Test
	void aGoalBehindItsPlanShowsTheRealShortfall() throws Exception {
		/* Made 1 June with 5,000 saved and 5,000 a month planned: four full months later 25,000 is expected. */
		JsonNode d = data("{\"goals\":[{\"id\":7,\"name\":\"Laptop\",\"target\":60000,\"saved\":15000,\"startSaved\":5000,"
				+ "\"monthly\":5000,\"createdDate\":\"2026-06-01\",\"targetDate\":\"2027-06-30\"}]}");

		Map<String, Object> goal = savingsGoals.goals(d, TODAY).get(0);

		assertEquals(rupees("25000.00"), goal.get("shouldHaveSaved"));
		assertEquals(rupees("10000.00"), goal.get("shortfall"));
		assertEquals("BEHIND", goal.get("status"));
	}

	@Test
	void aPastTargetDateAsksForANewDateAndAReachedGoalIsReached() throws Exception {
		JsonNode d = data("{\"goals\":["
				+ "{\"id\":1,\"name\":\"Phone\",\"target\":20000,\"saved\":8000,\"targetDate\":\"2026-01-01\"},"
				+ "{\"id\":2,\"name\":\"Fees\",\"target\":5000,\"saved\":5000}]}");

		List<Map<String, Object>> goals = savingsGoals.goals(d, TODAY);

		assertEquals("DATE_PASSED", goals.get(0).get("status"));
		assertFalse(goals.get(0).containsKey("requiredMonthly"));
		assertEquals("REACHED", goals.get(1).get("status"));

		/* The goal a payment touches is the unfinished one. */
		assertEquals("Phone", savingsGoals.active(goals).get("name"));
	}

	@Test
	void aPaymentNeverLowersSavingsItOnlyShowsWhatWouldHappen() throws Exception {
		JsonNode d = data("{\"goals\":[{\"id\":7,\"name\":\"Laptop\",\"target\":60000,\"saved\":20000,\"monthly\":5000}]}");

		Map<String, Object> goal = savingsGoals.goals(d, TODAY).get(0);
		Map<String, Object> hit = savingsGoals.impact(goal, rupees("8000"));

		assertEquals(rupees("12000.00"), hit.get("savedAfter"));
		assertEquals(rupees("40000.00"), hit.get("remainingBefore"));
		assertEquals(rupees("48000.00"), hit.get("remainingAfter"));
		assertEquals(2L, hit.get("delayMonths"), "8,000 at 5,000 a month is two months of saving");

		/* The goal itself is untouched. */
		assertEquals(rupees("20000.00"), goal.get("saved"));

		/* Without a monthly pace no delay is claimed. */
		Map<String, Object> noPace = savingsGoals.goals(
				data("{\"goals\":[{\"id\":7,\"name\":\"Laptop\",\"target\":60000,\"saved\":20000}]}"), TODAY).get(0);

		assertFalse(savingsGoals.impact(noPace, rupees("8000")).containsKey("delayMonths"));
	}
}
