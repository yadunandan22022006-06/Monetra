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
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetRulesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);
	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode data(String json) throws Exception {
		return mapper.readTree(json);
	}

	@Test
	void noLimitMeansUnknown() throws Exception {
		Map<String, Object> out = BudgetRules.check(data("{}"), new BigDecimal("500"), TODAY);
		assertEquals("unknown", out.get("level"));
	}

	@Test
	void overTheDailyLimit() throws Exception {
		JsonNode d = data("{\"dailyLimit\":300,\"expenses\":[{\"amount\":100,\"date\":\"2026-10-11\"}]}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("250"), TODAY);
		assertEquals("over", out.get("level"));
		assertEquals(new BigDecimal("-50.00"), out.get("remainingAfter"));
	}

	@Test
	void lowWhenLessThanTwentyPercentRemains() throws Exception {
		JsonNode d = data("{\"dailyLimit\":1000}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("850"), TODAY);
		assertEquals("low", out.get("level"));
	}

	@Test
	void okWhenComfortablyInside() throws Exception {
		JsonNode d = data("{\"dailyLimit\":1000}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("200"), TODAY);
		assertEquals("ok", out.get("level"));
	}

	@Test
	void expensesFromOtherDaysDoNotCountAsToday() throws Exception {
		JsonNode d = data("{\"dailyLimit\":500,\"expenses\":[{\"amount\":400,\"date\":\"2026-10-10\"}]}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("100"), TODAY);
		assertEquals(new BigDecimal("0.00"), out.get("spentToday"));
		assertEquals("ok", out.get("level"));
	}

	@Test
	void emiDueSoonAndMonthlyMoneyTooLowIsFlagged() throws Exception {
		JsonNode d = data("{\"dailyLimit\":5000,"
				+ "\"income\":[{\"amount\":3000,\"date\":\"2026-10-02\"}],"
				+ "\"loans\":[{\"name\":\"Laptop EMI\",\"amount\":2000,\"due\":\"2026-10-14\"}]}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("1500"), TODAY);
		assertTrue((Boolean) out.get("emiDueSoon"));
		assertTrue((Boolean) out.get("emiAtRisk"));
		assertEquals("Laptop EMI", out.get("nextEmiName"));
	}

	@Test
	void weeklyLimitCanBeBrokenWhileDailyIsFine() throws Exception {
		JsonNode d = data("{\"limits\":{\"daily\":1000,\"weekly\":1500},"
				+ "\"expenses\":[{\"amount\":900,\"date\":\"2026-10-08\"},{\"amount\":300,\"date\":\"2026-10-09\"}]}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("400"), TODAY);
		assertEquals("over", out.get("level"));
		assertEquals(java.util.List.of("weekly"), out.get("breaches"));
	}

	@Test
	void monthlyLimitIsTheBudgetForEmiPressure() throws Exception {
		JsonNode d = data("{\"limits\":{\"monthly\":4000},"
				+ "\"expenses\":[{\"amount\":2500,\"date\":\"2026-10-03\"}],"
				+ "\"loans\":[{\"name\":\"Bike EMI\",\"amount\":1500,\"due\":\"2026-10-12\"}]}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("500"), TODAY);
		assertEquals(new BigDecimal("1000.00"), out.get("monthLeftAfter"));
		assertTrue((Boolean) out.get("emiAtRisk"));
	}

	@Test
	void forecastProjectsMonthEndFromThePaceSoFar() throws Exception {
		/* 11 days into a 31-day month, 1100 spent: 100 a day, 3100 projected. */
		JsonNode d = data("{\"limits\":{\"monthly\":3000},\"expenses\":[{\"amount\":1100,\"date\":\"2026-10-05\"}]}");
		Map<String, Object> out = BudgetRules.forecast(d, TODAY);
		assertEquals("over", out.get("status"));
		assertEquals(new BigDecimal("3100.00"), out.get("projectedMonthEnd"));
		assertEquals(30, out.get("limitRunsOutOnDay"));
	}

	@Test
	void forecastWithoutALimitIsUnknown() throws Exception {
		Map<String, Object> out = BudgetRules.forecast(data("{}"), TODAY);
		assertEquals("unknown", out.get("status"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void paymentFactsShowTheAiEveryLimitLoanAndGoal() throws Exception {
		JsonNode d = data("{\"limits\":{\"daily\":100},"
				+ "\"expenses\":[{\"amount\":50,\"date\":\"2026-10-11\"}],"
				+ "\"loans\":[{\"name\":\"Bike EMI\",\"amount\":2000,\"due\":\"2026-10-14\"},"
				+ "{\"name\":\"Old Loan\",\"amount\":1000,\"due\":\"2026-10-01\"}],"
				+ "\"goals\":[{\"name\":\"Laptop\",\"target\":50000,\"saved\":12000}]}");

		Map<String, Object> out = BudgetRules.paymentFacts(d, new BigDecimal("10000"), TODAY);

		assertEquals("over", out.get("budgetStatus"));

		Map<String, Object> daily = ((List<Map<String, Object>>) out.get("limits")).get(0);
		assertEquals("daily", daily.get("period"));
		assertEquals(new BigDecimal("9950.00"), daily.get("overLimitByInr"));
		assertEquals(new BigDecimal("100.0"), daily.get("paymentIsTimesTheLimit"));
		assertEquals(List.of("weekly", "monthly", "yearly"), out.get("limitsNotSet"));

		List<Map<String, Object>> loans = (List<Map<String, Object>>) out.get("loans");
		assertEquals(3L, loans.get(0).get("dueInDays"));
		assertEquals(10L, loans.get(1).get("overdueByDays"));
		assertEquals(new BigDecimal("3000.00"), out.get("emiTotalPerMonthInr"));
		assertEquals(new BigDecimal("36000.00"), out.get("emiTotalPerYearInr"));
		assertEquals(new BigDecimal("98.63"), out.get("emiNeedsSavingPerDayInr"));

		assertEquals(new BigDecimal("38000.00"), out.get("goalsStillNeededInr"));

		/* No monthly limit and no income recorded, so there is no month budget to report. */
		assertFalse(out.containsKey("monthBudgetLeftAfterThisPaymentInr"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void paymentFactsInsideTheLimitSayWhatIsLeft() throws Exception {
		JsonNode d = data("{\"limits\":{\"daily\":500,\"monthly\":4000}}");

		Map<String, Object> out = BudgetRules.paymentFacts(d, new BigDecimal("100"), TODAY);

		Map<String, Object> daily = ((List<Map<String, Object>>) out.get("limits")).get(0);
		assertEquals(new BigDecimal("400.00"), daily.get("leftAfterThisPaymentInr"));
		assertFalse(daily.containsKey("overLimitByInr"));
		assertEquals(new BigDecimal("3900.00"), out.get("monthBudgetLeftAfterThisPaymentInr"));
		assertEquals(List.of(), out.get("loans"));
	}

	@Test
	void paymentBriefTurnsTheFactsIntoPlainSentences() throws Exception {
		JsonNode d = data("{\"limits\":{\"daily\":100,\"monthly\":20000},"
				+ "\"expenses\":[{\"amount\":50,\"date\":\"2026-10-11\"}],"
				+ "\"loans\":[{\"name\":\"Bike EMI\",\"amount\":2000,\"due\":\"2026-10-14\"}],"
				+ "\"goals\":[{\"name\":\"Laptop\",\"target\":50000,\"saved\":12000}]}");

		Map<String, Object> facts = BudgetRules.paymentFacts(d, new BigDecimal("10000"), TODAY);
		facts.put("recipientName", "Ravi");

		String brief = String.join("\n", BudgetRules.paymentBrief(facts));

		/* Only what matters most: the broken limit, the EMI due soon and the goal it strains. */
		assertTrue(brief.contains("You are about to pay ₹10,000 to Ravi. The payment has not been made yet."), brief);
		assertTrue(brief.contains("goes OVER your daily limit of ₹100 by ₹9,950. The payment alone is about 100 times that limit."), brief);
		assertTrue(brief.contains("Your EMI \"Bike EMI\" of ₹2,000 is due in 3 days, and this payment still leaves room for it this month."), brief);
		assertTrue(brief.contains("Your savings goal \"Laptop\" still needs ₹38,000."), brief);

		/* The monthly limit is fine, so it is left out. */
		assertFalse(brief.contains("monthly"), brief);
	}

	@Test
	void paymentBriefForAPaymentThatFitsIsShort() throws Exception {
		JsonNode d = data("{\"limits\":{\"daily\":500},"
				+ "\"loans\":[{\"name\":\"Bike EMI\",\"amount\":2000,\"due\":\"2026-12-14\"}],"
				+ "\"goals\":[{\"name\":\"Laptop\",\"target\":50000,\"saved\":12000}]}");

		List<String> brief = BudgetRules.paymentBrief(BudgetRules.paymentFacts(d, new BigDecimal("100"), TODAY));

		/* Nothing is strained and the EMI is far away: the payment and the limit are all there is to say. */
		assertEquals(2, brief.size(), brief.toString());
		assertTrue(brief.get(1).contains("fits every spending limit you set. After it, ₹400 is left of your daily limit of ₹500."), brief.toString());
	}

	@Test
	void paymentBriefSaysWhenNoLimitIsSet() throws Exception {
		Map<String, Object> facts = BudgetRules.paymentFacts(data("{}"), new BigDecimal("250.50"), TODAY);

		String brief = String.join("\n", BudgetRules.paymentBrief(facts));

		assertTrue(brief.contains("You are about to pay ₹250.50."), brief);
		assertTrue(brief.contains("No spending limit is set"), brief);
	}

	@Test
	void farAwayEmiIsNotFlagged() throws Exception {
		JsonNode d = data("{\"income\":[{\"amount\":3000,\"date\":\"2026-10-02\"}],"
				+ "\"loans\":[{\"name\":\"Laptop EMI\",\"amount\":2000,\"due\":\"2026-11-14\"}]}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("1500"), TODAY);
		assertFalse((Boolean) out.get("emiDueSoon"));
		assertFalse((Boolean) out.get("emiAtRisk"));
	}
}
