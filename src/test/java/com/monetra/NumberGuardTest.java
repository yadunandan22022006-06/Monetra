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
	void aReplyThatSoftensABrokenLimitIsCaught() {
		/* Java says the payment is over the limit. */
		assertTrue(NumberGuard.agreesWithVerdict("This payment goes over your daily limit of ₹500 by ₹9,500.", true));
		assertTrue(NumberGuard.agreesWithVerdict("You would exceed your daily limit. You may want to wait.", true));

		/* Advice to get back inside the limit is not a claim that the payment fits. */
		assertTrue(NumberGuard.agreesWithVerdict(
				"This payment goes over your daily limit by ₹200. You could pay less to stay within your limit.", true));

		assertFalse(NumberGuard.agreesWithVerdict("You're close to your daily limit, but you're still within it.", true));
		assertFalse(NumberGuard.agreesWithVerdict("You are close to exceeding your daily spending limit of ₹500.", true));
		assertFalse(NumberGuard.agreesWithVerdict("This is a large payment compared to your daily limit.", true));
	}

	@Test
	void aReplyThatClaimsABrokenLimitWhenItFitsIsCaught() {
		/* Java says the payment fits every limit. */
		assertTrue(NumberGuard.agreesWithVerdict("You're good to go. You'll have ₹350 left in your daily limit.", false));
		assertTrue(NumberGuard.agreesWithVerdict("This payment does not exceed any of your limits.", false));
		assertTrue(NumberGuard.agreesWithVerdict("It won't go over your limit, and your EMI is overdue by 2 days.", false));

		assertFalse(NumberGuard.agreesWithVerdict("You have exceeded your daily spending limit by ₹50.", false));
		assertFalse(NumberGuard.agreesWithVerdict("This payment goes over your weekly limit.", false));
	}

	@Test
	void aReplyThatDropsAnEmiWarningIsCaught() {
		String facts = "After this payment only ₹3,000 of this month's budget is left, which is NOT enough for your EMIs "
				+ "of ₹5,000 due in the next 30 days.";

		assertTrue(NumberGuard.keepsWarnings(
				"After this payment only ₹3,000 is left this month, which is not enough for your EMIs of ₹5,000.", facts));

		assertFalse(NumberGuard.keepsWarnings("You're good to go with this payment.", facts));
		assertFalse(NumberGuard.keepsWarnings("You'll have ₹3,000 left this month and EMIs of ₹5,000 coming up.", facts));
		assertFalse(NumberGuard.keepsWarnings("You're good to go, though ₹3,000 is not enough for your EMIs.", facts));

		/* With no warning in the facts there is nothing to drop. */
		assertTrue(NumberGuard.keepsWarnings("You're good to go with this payment.", "This payment fits every spending limit you set."));
	}

	@Test
	void adviceMayLeaveTheFactsOutButNeverCallsARiskyPaymentFine() {
		/* A broken limit: advice does not have to repeat it, but must not wave the payment through. */
		assertTrue(NumberGuard.fitsAdvice("You could pay ₹100 or less today and wait until tomorrow for the rest.", true, FACTS));
		assertTrue(NumberGuard.fitsAdvice("You may want to wait so you stay within your limit.", true, FACTS));

		assertFalse(NumberGuard.fitsAdvice("This payment is within your limit, so you can go ahead.", true, FACTS));
		assertFalse(NumberGuard.fitsAdvice("You're good to go.", true, FACTS));
		assertFalse(NumberGuard.fitsAdvice("It looks fine to pay now.", true, FACTS));

		/* Something NOT covered: the same. */
		String shortFacts = "After this payment only ₹3,000 is left, which is NOT enough for your EMIs of ₹5,000.";

		assertTrue(NumberGuard.fitsAdvice("You could keep ₹5,000 aside for your EMIs before paying.", false, shortFacts));
		assertFalse(NumberGuard.fitsAdvice("No problem, you can pay.", false, shortFacts));

		/* Nothing broken: advice must not claim a limit is broken. */
		assertTrue(NumberGuard.fitsAdvice("This fits your limits; keep the rest for later.", false, FACTS));
		assertFalse(NumberGuard.fitsAdvice("This goes over your daily limit.", false, FACTS));
	}

	@Test
	void aReplyThatListsTheBoxesAgainIsCaught() {
		String points = "Risk level: HIGH.\nIt threatens to cut your electricity. Electricity boards do not send threats.\n"
				+ "It has a shortened link that hides where it really goes.\nIt pushes you to act fast.";

		assertTrue(NumberGuard.repeatsFacts("Here are the points:\n\nIt threatens to cut your electricity.\n"
				+ "It has a shortened link that hides where it really goes.", points));

		assertFalse(NumberGuard.repeatsFacts("Do not open the link or pay: check with your electricity board in its own app.", points));
	}

	@Test
	void thePlannerNeverSaysBuyTodayWhenThatIsNotSafe() {
		assertFalse(NumberGuard.keepsBuyDay("You can buy it today.", false));
		assertFalse(NumberGuard.keepsBuyDay("Go ahead and get it right now!", false));

		assertTrue(NumberGuard.keepsBuyDay("Buying it today is not safe. Put aside ₹350 a day and buy it on 20 Dec 2026.", false));
		assertTrue(NumberGuard.keepsBuyDay("You can buy it today without missing any EMI.", true));
	}

	@Test
	void scamShieldsAiNeverCallsARiskyMessageSafe() {
		assertFalse(NumberGuard.keepsScamRisk("This message looks genuine.", "HIGH"));
		assertFalse(NumberGuard.keepsScamRisk("It is probably not a scam, but be careful.", "MEDIUM"));

		assertTrue(NumberGuard.keepsScamRisk("This message is not safe. Do not open the link.", "HIGH"));
		assertTrue(NumberGuard.keepsScamRisk("Nothing in it looks like a scam, so it seems safe.", "LOW"));
	}

	@Test
	void anInventedNumberIsCaught() {
		assertFalse(NumberGuard.onlyKnownNumbers("This is over your daily limit of ₹5,000.", FACTS));
		assertFalse(NumberGuard.onlyKnownNumbers("That is about 20 times your limit.", FACTS));
		assertFalse(NumberGuard.onlyKnownNumbers("You will have ₹349.50 left.", FACTS));
	}
}
