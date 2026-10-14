package com.monetra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * My bank accounts and Check Balance against a stand-in for Setu's Account Aggregator gateway:
 * linking through a consent, the balance the bank returns, at most one request per refresh
 * interval, and failures that never show an invented balance.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-bank",
				"spring.mail.host=localhost",
				"paypal.client-id=",
				"paypal.client-secret=",
				"anthropic.api-key=",
				"ai.base-url=",
				"setu.aa.client-id=test-client",
				"setu.aa.client-secret=test-secret",
				"setu.aa.product-instance-id=pid-test",
				"setu.aa.min-refresh-minutes=60",
				"setu.aa.wait-seconds=1",
				"setu.aa.poll-millis=50"
		})
class BankFlowTest {

	private static final FakeSetu SETU = new FakeSetu();

	@DynamicPropertySource
	static void setuProperties(DynamicPropertyRegistry registry) {
		registry.add("setu.aa.base-url", SETU::baseUrl);
		registry.add("setu.aa.token-url", () -> SETU.baseUrl() + "/login");
	}

	@AfterAll
	static void stop() {
		SETU.stop();
	}

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	@BeforeEach
	void reset() {
		SETU.sessionMode = "COMPLETED";
		SETU.balance = "101666.33";
	}

	private Http user(String name, String phone) throws Exception {
		String email = name + "@test.dev";

		if (users.findByEmail(email).isEmpty()) {
			users.save(new UserAccount(email, new BCryptPasswordEncoder().encode("password-" + name), phone, name));
		}

		return Http.login(port, email, "password-" + name);
	}

	/** Links an account for this user and approves the consent on the stand-in's "screens". */
	private String linkAndApprove(Http http) throws Exception {
		HttpResponse<String> link = http.post("/api/bank/link", "{}");

		assertEquals(200, link.statusCode(), link.body());

		String consentId = Http.field(link.body(), "consentId");

		SETU.consentStatus.put(consentId, "ACTIVE");

		assertTrue(http.post("/api/bank/consents/check", "{}").body().contains("Your bank account was linked."));

		return consentId;
	}

	@Test
	void linkingUsesSetusConsentScreensAndTheBalanceComesFromTheBank() throws Exception {
		Http asha = user("asha", "9000000081");

		HttpResponse<String> link = asha.post("/api/bank/link", "{}");

		assertEquals(200, link.statusCode(), link.body());
		assertTrue(link.body().contains("https://fiu-sandbox.setu.co/consents/webview/"), link.body());

		/* Setu's documented calls: login with the client credentials, then a consent with the user's mobile number. */
		String token = SETU.tokenRequests.get(0);

		assertTrue(token.startsWith("client=bridge"), token);
		assertTrue(token.contains("\"clientID\":\"test-client\"") && token.contains("\"grant_type\":\"client_credentials\""), token);

		String consent = SETU.consentRequests.get(SETU.consentRequests.size() - 1);

		assertTrue(consent.contains("\"vua\":\"9000000081\""), consent);
		assertTrue(consent.contains("\"redirectUrl\":\"http://localhost:" + port + "/?bank=return\""), consent);
		assertTrue(consent.contains("\"consentDuration\":{\"unit\":\"MONTH\",\"value\":\"12\"}"), consent);
		assertEquals("Bearer setu-token", SETU.lastHeaders.get("authorization"));
		assertEquals("pid-test", SETU.lastHeaders.get("x-product-instance-id"));

		/* Not approved yet: nothing is linked and no balance is asked for. */
		int before = SETU.sessionsCreated();

		String waiting = asha.post("/api/bank/balance", "{}").body();

		assertTrue(waiting.contains("\"state\":\"NO_ACCOUNT\""), waiting);
		assertTrue(waiting.contains("\"account\":null"), waiting);
		assertEquals(before, SETU.sessionsCreated());

		/* Approved on Setu's screens: the account is linked and its balance is the bank's. */
		SETU.consentStatus.put(Http.field(link.body(), "consentId"), "ACTIVE");

		String linked = asha.post("/api/bank/consents/check", "{}").body();

		assertTrue(linked.contains("\"maskedAccNumber\":\"XXXXXXXX1234\""), linked);
		assertTrue(linked.contains("\"primary\":true"), linked);

		String balance = asha.post("/api/bank/balance", "{}").body();

		assertTrue(balance.contains("\"state\":\"OK\""), balance);
		assertTrue(balance.contains("\"balance\":101666.33"), balance);
		assertTrue(balance.contains("\"bank\":\"setu-fip\""), balance);
		assertTrue(balance.contains("\"source\":\"SETU_SANDBOX\""), balance);
		assertFalse(balance.contains("\"lastCheckedAt\":null"), balance);
		assertEquals(before + 1, SETU.sessionsCreated());

		/* Checking again within the hour shows the same balance without asking the bank again. */
		SETU.balance = "99.00";

		String again = asha.post("/api/bank/balance", "{}").body();

		assertTrue(again.contains("\"balance\":101666.33"), again);
		assertTrue(again.contains("\"nextUpdateAt\""), again);
		assertEquals(before + 1, SETU.sessionsCreated());
	}

	@Test
	void aFailedCheckNeverShowsAnInventedBalance() throws Exception {
		Http ravi = user("ravi", "9000000082");

		linkAndApprove(ravi);

		SETU.sessionMode = "FAILED";

		String failed = ravi.post("/api/bank/balance", "{}").body();

		assertTrue(failed.contains("\"state\":\"ERROR\""), failed);
		assertTrue(failed.contains("The bank could not send the balance"), failed);
		assertTrue(failed.contains("\"balance\":null"), failed);
		assertTrue(failed.contains("\"lastCheckedAt\":null"), failed);
		assertTrue(failed.contains("\"stale\":false"), failed);

		/* Right after a failure Setu is not asked again at once. */
		int sessions = SETU.sessionsCreated();

		assertTrue(ravi.post("/api/bank/balance", "{}").body().contains("\"state\":\"ERROR\""));
		assertEquals(sessions, SETU.sessionsCreated());
	}

	@Test
	void anErrorFromSetuIsReportedAsItIs() throws Exception {
		Http mira = user("mira", "9000000083");

		linkAndApprove(mira);

		SETU.sessionMode = "ERROR";

		String failed = mira.post("/api/bank/balance", "{}").body();

		assertTrue(failed.contains("\"state\":\"ERROR\""), failed);
		assertTrue(failed.contains("Setu: FIP not reachable"), failed);
		assertTrue(failed.contains("\"balance\":null"), failed);
	}

	@Test
	void aBalanceStillBeingPreparedIsCollectedLaterWithoutAskingAgain() throws Exception {
		Http kiran = user("kiran", "9000000084");

		linkAndApprove(kiran);

		SETU.sessionMode = "PENDING";

		String waiting = kiran.post("/api/bank/balance", "{}").body();

		assertTrue(waiting.contains("\"state\":\"WAITING\""), waiting);
		assertTrue(waiting.contains("\"balance\":null"), waiting);

		int sessions = SETU.sessionsCreated();

		/* The bank is ready now: the same request is read, no new one is made. */
		SETU.sessionMode = "COMPLETED";

		String done = kiran.post("/api/bank/balance", "{}").body();

		assertTrue(done.contains("\"state\":\"OK\""), done);
		assertTrue(done.contains("\"balance\":101666.33"), done);
		assertEquals(sessions, SETU.sessionsCreated());
	}

	@Test
	void aDeclinedConsentLinksNothing() throws Exception {
		Http neha = user("neha", "9000000085");

		HttpResponse<String> link = neha.post("/api/bank/link", "{}");

		SETU.consentStatus.put(Http.field(link.body(), "consentId"), "REJECTED");

		String checked = neha.post("/api/bank/consents/check", "{}").body();

		assertTrue(checked.contains("declined"), checked);
		assertTrue(checked.contains("\"accounts\":[]"), checked);
	}

	@Test
	void removingAnAccountRevokesItsConsentAndOthersSeeNothing() throws Exception {
		Http omar = user("omar", "9000000086");

		String consentId = linkAndApprove(omar);

		String balance = omar.post("/api/bank/balance", "{}").body();

		String accountId = balance.replaceAll(".*\"account\":\\{\"id\":(\\d+).*", "$1");

		/* Another user cannot see or read this account. */
		Http lina = user("lina", "9000000087");

		assertTrue(lina.get("/api/bank").body().contains("\"accounts\":[]"));
		assertEquals(404, lina.post("/api/bank/balance", "{\"accountId\":" + accountId + "}").statusCode());
		assertEquals(404, lina.delete("/api/bank/accounts/" + accountId).statusCode());
		assertEquals(401, new Http(port).get("/api/bank").statusCode());

		/* Removing revokes the consent at Setu first. */
		HttpResponse<String> removed = omar.delete("/api/bank/accounts/" + accountId);

		assertEquals(200, removed.statusCode(), removed.body());
		assertTrue(SETU.revoked.contains(consentId));
		assertTrue(removed.body().contains("\"accounts\":[]"), removed.body());
	}
}
