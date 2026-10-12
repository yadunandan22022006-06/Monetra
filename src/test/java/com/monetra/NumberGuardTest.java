package com.monetra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberGuardTest {

	private static final String FACTS = "The payment is ₹10,000. The daily limit is ₹500 and ₹150.50 of it was "
			+ "already spent. The EMI is due in 3 days.";

	@Test
	void repeatingTheFactsIsAccepted() {
		assertTrue(NumberGuard.onlyKnownNumbers(
				"You are paying ₹10000.00, which is over your ₹500 limit. ₹150.5 was spent and an EMI is due in 3 days.", FACTS));
	}

	@Test
	void aReplyWithoutNumbersIsAccepted() {
		assertTrue(NumberGuard.onlyKnownNumbers("This goes over your daily limit. You may want to wait.", FACTS));
	}

	@Test
	void anInventedNumberIsCaught() {
		assertFalse(NumberGuard.onlyKnownNumbers("This is over your daily limit of ₹5,000.", FACTS));
		assertFalse(NumberGuard.onlyKnownNumbers("That is about 20 times your limit.", FACTS));
		assertFalse(NumberGuard.onlyKnownNumbers("You will have ₹349.50 left.", FACTS));
	}
}
