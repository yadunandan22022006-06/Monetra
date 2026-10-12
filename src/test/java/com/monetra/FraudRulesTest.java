package com.monetra;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudRulesTest {

	private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

	private static FraudRules.Past done(String key, String amount, long minutesAgo) {
		return new FraudRules.Past(key, new BigDecimal(amount), NOW.minusSeconds(minutesAgo * 60), "COMPLETED");
	}

	@Test
	void smallPaymentToNewPersonIsFine() {
		assertTrue(FraudRules.assess(List.of(), "P:9876543210", new BigDecimal("500"), NOW).isEmpty());
	}

	@Test
	void largeFirstPaymentIsFlagged() {
		List<String> flags = FraudRules.assess(List.of(), "P:9876543210", new BigDecimal("6000"), NOW);
		assertEquals(1, flags.size());
		assertTrue(flags.get(0).contains("first payment"));
	}

	@Test
	void largePaymentToKnownRecipientIsNotFlaggedAsNew() {
		List<FraudRules.Past> past = List.of(done("P:9876543210", "5000", 60 * 24));
		assertTrue(FraudRules.assess(past, "P:9876543210", new BigDecimal("6000"), NOW).isEmpty());
	}

	@Test
	void amountFarAboveUsualIsFlagged() {
		List<FraudRules.Past> past = List.of(
				done("P:1111111111", "200", 3000),
				done("P:2222222222", "300", 4000),
				done("P:3333333333", "400", 5000));

		List<String> flags = FraudRules.assess(past, "P:1111111111", new BigDecimal("1500"), NOW);
		assertEquals(1, flags.size());
		assertTrue(flags.get(0).contains("3 times"));
	}

	@Test
	void usualSizedPaymentIsNotFlagged() {
		List<FraudRules.Past> past = List.of(
				done("P:1111111111", "200", 3000),
				done("P:2222222222", "300", 4000),
				done("P:3333333333", "400", 5000));

		assertTrue(FraudRules.assess(past, "P:1111111111", new BigDecimal("600"), NOW).isEmpty());
	}

	@Test
	void manyPaymentsInTenMinutesAreFlagged() {
		List<FraudRules.Past> past = new ArrayList<>();

		for (int i = 0; i < 3; i++) {
			past.add(new FraudRules.Past("P:" + i, new BigDecimal("100"), NOW.minusSeconds(60L * (i + 1)), "AWAITING_APPROVAL"));
		}

		List<String> flags = FraudRules.assess(past, "P:9", new BigDecimal("100"), NOW);
		assertEquals(1, flags.size());
		assertTrue(flags.get(0).contains("10 minutes"));
	}

	@Test
	void cancelledPaymentsDoNotCountAsRapid() {
		List<FraudRules.Past> past = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			past.add(new FraudRules.Past("P:" + i, new BigDecimal("100"), NOW.minusSeconds(60L * (i + 1)), "CANCELLED"));
		}

		assertTrue(FraudRules.assess(past, "P:9", new BigDecimal("100"), NOW).isEmpty());
	}
}
