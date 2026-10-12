package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
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
	void farAwayEmiIsNotFlagged() throws Exception {
		JsonNode d = data("{\"income\":[{\"amount\":3000,\"date\":\"2026-10-02\"}],"
				+ "\"loans\":[{\"name\":\"Laptop EMI\",\"amount\":2000,\"due\":\"2026-11-14\"}]}");
		Map<String, Object> out = BudgetRules.check(d, new BigDecimal("1500"), TODAY);
		assertFalse((Boolean) out.get("emiDueSoon"));
		assertFalse((Boolean) out.get("emiAtRisk"));
	}
}
