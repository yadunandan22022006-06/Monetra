package com.monetra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScamShieldServiceTest {

	private final ScamShieldService shield = new ScamShieldService();

	private static String rules(Map<String, Object> result) {
		StringBuilder out = new StringBuilder();

		for (Object o : (List<?>) result.get("reasons")) {
			out.append(((Map<?, ?>) o).get("rule")).append(' ');
		}

		return out.toString().trim();
	}

	@Test
	void anElectricityThreatWithAShortLinkIsHighRiskAndSaysWhyFirst() {
		Map<String, Object> r = shield.check("Dear customer, your electricity power will be disconnected tonight at 9:30 pm "
				+ "because your last bill was not updated. Pay immediately at http://bit.ly/eb-pay");

		assertEquals("HIGH", r.get("level"));
		assertEquals("ELECTRICITY SHORT_LINK URGENT", rules(r));
		assertTrue(String.valueOf(r.get("reason")).startsWith("It threatens to cut your electricity."), String.valueOf(r.get("reason")));
		assertTrue(((List<?>) r.get("phrases")).contains("bit.ly"), String.valueOf(r.get("phrases")));
	}

	@Test
	void aPinToReceiveAPrizeIsHighRisk() {
		Map<String, Object> r = shield.check("Congratulations! You have won Rs 25,00,000 in the KBC lottery. "
				+ "To receive the prize money, enter your UPI PIN.");

		assertEquals("HIGH", r.get("level"));
		assertTrue(rules(r).startsWith("PIN"), rules(r));
		assertTrue(rules(r).contains("RECEIVE"), rules(r));
		assertTrue(rules(r).contains("PRIZE"), rules(r));
	}

	@Test
	void aScreenSharingAppAndABlockedAccountAreHighRisk() {
		Map<String, Object> r = shield.check("Install AnyDesk so our officer can help with your KYC, otherwise your account will be blocked.");

		assertEquals("HIGH", r.get("level"));
		assertTrue(rules(r).contains("REMOTE") && rules(r).contains("KYC"), rules(r));
	}

	@Test
	void aFeeToClaimAPrizeIsHighRisk() {
		Map<String, Object> r = shield.check("Congratulations! You won Rs 50000 in KBC lottery. "
				+ "Pay Rs 999 processing fee to claim. Call 9876500000 now.");

		assertEquals("HIGH", r.get("level"));
		assertTrue(rules(r).contains("FEE"), rules(r));

		/* Advice not to pay is not a request to pay. */
		assertEquals("LOW", shield.check("Never pay any fee to get a refund.").get("level"));
	}

	@Test
	void aJobOfferIsMediumRisk() {
		Map<String, Object> r = shield.check("Part time job from home! Just like and earn, message us on WhatsApp.");

		assertEquals("MEDIUM", r.get("level"));
		assertEquals("JOB", rules(r));
	}

	@Test
	void aGenuineOtpMessageThatSaysDoNotShareIsLowRisk() {
		assertEquals("LOW", shield.check("482913 is your OTP for login. Do not share this OTP with anyone. - Your Bank").get("level"));
		assertEquals("LOW", shield.check("Never share your UPI PIN with anyone, even bank staff.").get("level"));
		assertEquals("LOW", shield.check("Are we still meeting for lunch tomorrow at 1?").get("level"));
	}

	@Test
	void anOrdinaryMessageHasNoReasons() {
		Map<String, Object> r = shield.check("Your order has been delivered. Thank you for shopping with us.");

		assertEquals("LOW", r.get("level"));
		assertTrue(((List<?>) r.get("reasons")).isEmpty());
		assertTrue(String.valueOf(r.get("reason")).startsWith("Nothing that payment scams usually do"));
	}
}
