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

/** The AI Financial Guardian against a fake Anthropic server whose replies the test scripts. */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-agent",
				"spring.mail.host=localhost",
				"paypal.client-id=",
				"paypal.client-secret=",
				"anthropic.api-key=test-key"
		})
class AgentFlowTest {

	private static final FakeAnthropic AI = new FakeAnthropic();

	@DynamicPropertySource
	static void aiProperties(DynamicPropertyRegistry registry) {
		registry.add("anthropic.base-url", AI::baseUrl);
	}

	@AfterAll
	static void stop() {
		AI.stop();
	}

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	private Http alex;

	@BeforeEach
	void setUp() throws Exception {
		AI.reset();

		if (users.findByEmail("alex@test.dev").isEmpty()) {
			BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

			users.save(new UserAccount("alex@test.dev", enc.encode("password-a"), "9000000081", "Alex Student"));
			users.save(new UserAccount("ravi.agent@test.dev", enc.encode("password-r"), "9000000082", "Ravi Agent"));
		}

		alex = Http.login(port, "alex@test.dev", "password-a");
	}

	private HttpResponse<String> chat(String message) throws Exception {
		return alex.post("/api/agent/chat", "{\"message\":\"" + message + "\"}");
	}

	@Test
	void answersFromRealNumbersUsingTools() throws Exception {
		assertEquals(200, alex.put("/api/data", "{\"limits\":{\"daily\":500},\"expenses\":[]}").statusCode());

		AI.reply(FakeAnthropic.toolUse("t1", "check_affordability", "{\"amountInr\":2000,\"purpose\":\"shoes\"}"));
		AI.reply(FakeAnthropic.text("That is over your daily limit."));

		HttpResponse<String> r = chat("Can I afford 2000 for shoes?");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("That is over your daily limit."), r.body());
		assertTrue(r.body().contains("check_affordability"), r.body());

		assertEquals(2, AI.requests.size());

		/* The first call told the model about the tools and the rules. */
		assertTrue(AI.requests.get(0).contains("check_affordability"));
		assertTrue(AI.requests.get(0).contains("prepare_payment"));
		assertTrue(AI.requests.get(0).contains("cannot send money"));

		/* The second call carried the tool's answer, worked out in Java from the saved data. */
		assertTrue(AI.requests.get(1).contains("tool_result"));
		assertTrue(AI.requests.get(1).contains("not_affordable"), AI.requests.get(1));
		assertTrue(AI.requests.get(1).contains("daily"), AI.requests.get(1));
	}

	@Test
	void preparesAPaymentButNeverSendsIt() throws Exception {
		AI.reply(FakeAnthropic.toolUse("t1", "prepare_payment",
				"{\"recipient\":\"9000000082\",\"amountInr\":800,\"note\":\"Books\"}"));
		AI.reply(FakeAnthropic.text("I prepared it. Please review and confirm."));

		HttpResponse<String> r = chat("Prepare a payment of 800 to 9000000082");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("\"recipientName\":\"Ravi Agent\""), r.body());
		assertTrue(r.body().contains("\"recipientPhone\":\"9000000082\""), r.body());
		assertTrue(r.body().contains("\"amountInr\":800"), r.body());

		/* Nothing was created: no payment exists. */
		assertEquals("[]", alex.get("/api/payments").body());

		assertTrue(AI.requests.get(1).contains("Nothing was sent"));
	}

	@Test
	void anUnknownContactIsReportedBackToTheModelInsteadOfGuessing() throws Exception {
		AI.reply(FakeAnthropic.toolUse("t1", "prepare_payment", "{\"recipient\":\"Nobody\",\"amountInr\":100}"));
		AI.reply(FakeAnthropic.text("Which mobile number should I use?"));

		HttpResponse<String> r = chat("Pay Nobody 100");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("Which mobile number should I use?"), r.body());
		assertTrue(r.body().contains("\"payments\":[]"), r.body());
		assertTrue(AI.requests.get(1).contains("is_error"), AI.requests.get(1));
		assertTrue(AI.requests.get(1).contains("mobile number"), AI.requests.get(1));
	}

	@Test
	void aReminderIsPreparedButNotSavedUntilTheUserConfirms() throws Exception {
		AI.reply(FakeAnthropic.toolUse("t1", "prepare_reminder",
				"{\"name\":\"Money for home\",\"amountInr\":3000,\"frequency\":\"MONTHLY\",\"dayOfMonth\":10,\"category\":\"Family\"}"));
		AI.reply(FakeAnthropic.text("Please confirm this reminder."));

		HttpResponse<String> r = chat("Remind me to give 3000 to my family on the 10th of every month");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("\"name\":\"Money for home\""), r.body());
		assertTrue(r.body().contains("\"frequency\":\"MONTHLY\""), r.body());
		assertTrue(r.body().contains("-10\""), "the first due date is on the 10th: " + r.body());

		assertFalse(alex.get("/api/home/overview").body().contains("Money for home"), "nothing is saved yet");
	}

	@Test
	void theExplainEndpointsUseTheSameClient() throws Exception {
		AI.reply(FakeAnthropic.text("You are inside your limit."));

		HttpResponse<String> r = alex.post("/api/guardian/explain",
				"{\"amountInr\":100,\"dailyLimit\":500,\"spentToday\":0,\"remainingAfter\":400,\"level\":\"ok\"}");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("You are inside your limit."), r.body());
	}

	@Test
	void aBrokenAiServerGivesAFriendlyError() throws Exception {
		/* The fake has no scripted reply, so it answers with an error. */
		HttpResponse<String> r = chat("Hello");

		assertEquals(502, r.statusCode(), r.body());
		assertTrue(r.body().contains("not available"), r.body());
	}
}
