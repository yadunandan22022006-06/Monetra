package com.monetra;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeExpenseRulesTest {

	private static LocalDate d(String s) {
		return LocalDate.parse(s);
	}

	@Test
	void oneTimeExpenseHasOneDueDate() {
		assertEquals(List.of(d("2026-10-15")),
				HomeExpenseRules.occurrences("ONCE", d("2026-10-15"), d("2026-10-01"), d("2026-10-31")));

		assertTrue(HomeExpenseRules.occurrences("ONCE", d("2026-11-15"), d("2026-10-01"), d("2026-10-31")).isEmpty());
	}

	@Test
	void dailyExpenseIsDueEveryDayFromItsStart() {
		List<LocalDate> days = HomeExpenseRules.occurrences("DAILY", d("2026-10-10"), d("2026-10-08"), d("2026-10-12"));

		assertEquals(List.of(d("2026-10-10"), d("2026-10-11"), d("2026-10-12")), days);
	}

	@Test
	void weeklyExpenseKeepsItsWeekday() {
		List<LocalDate> days = HomeExpenseRules.occurrences("WEEKLY", d("2026-10-01"), d("2026-10-10"), d("2026-10-31"));

		assertEquals(List.of(d("2026-10-15"), d("2026-10-22"), d("2026-10-29")), days);
	}

	@Test
	void monthlyExpenseKeepsItsDayAndShortMonthsUseTheLastDay() {
		assertEquals(List.of(d("2026-10-05"), d("2026-11-05")),
				HomeExpenseRules.occurrences("MONTHLY", d("2026-08-05"), d("2026-10-01"), d("2026-11-30")));

		assertEquals(List.of(d("2027-02-28"), d("2027-03-31")),
				HomeExpenseRules.occurrences("MONTHLY", d("2026-10-31"), d("2027-02-01"), d("2027-03-31")));
	}

	@Test
	void yearlyExpenseComesOncePerYear() {
		assertEquals(List.of(d("2026-12-01"), d("2027-12-01")),
				HomeExpenseRules.occurrences("YEARLY", d("2024-12-01"), d("2026-01-01"), d("2027-12-31")));
	}

	@Test
	void nothingIsDueBeforeTheStartDate() {
		assertTrue(HomeExpenseRules.occurrences("MONTHLY", d("2026-12-05"), d("2026-10-01"), d("2026-11-30")).isEmpty());
	}

	@Test
	void statusIsPaidDueTodayOverdueOrUpcoming() {
		LocalDate today = d("2026-10-11");

		assertEquals("PAID", HomeExpenseRules.status(d("2026-10-01"), true, today));
		assertEquals("OVERDUE", HomeExpenseRules.status(d("2026-10-10"), false, today));
		assertEquals("DUE_TODAY", HomeExpenseRules.status(d("2026-10-11"), false, today));
		assertEquals("UPCOMING", HomeExpenseRules.status(d("2026-10-12"), false, today));
	}
}
