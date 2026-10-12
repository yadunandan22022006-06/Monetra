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
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same AI Financial Guardian, but through a free OpenAI-compatible provider (Gemini, Groq, Ollama...). */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-freeai",
				"spring.mail.host=localhost",
				"paypal.client-id=",
				"paypal.client-secret=",
				"anthropic.api-key=",
				"ai.api-key=free-test-key",
				"ai.model=free-model"
		})
class FreeAiFlowTest {

	private static final FakeOpenAi AI = new FakeOpenAi();

	@DynamicPropertySource
	static void aiProperties(DynamicPropertyRegistry registry) {
		registry.add("ai.base-url", AI::baseUrl);
	}

	@AfterAll
	static void stop() {
		AI.stop();
	}

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	private Http zoe;

	@BeforeEach
	void setUp() throws Exception {
		AI.reset();

		if (users.findByEmail("zoe@test.dev").isEmpty()) {
			BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

			users.save(new UserAccount("zoe@test.dev", enc.encode("password-z"), "9000000091", "Zoe Student"));
			users.save(new UserAccount("ravi.free@test.dev", enc.encode("password-r"), "9000000092", "Ravi Free"));
		}

		zoe = Http.login(port, "zoe@test.dev", "password-z");
	}

	@Test
	void theChatWorksThroughAFreeProviderUsingTools() throws Exception {
		assertEquals(200, zoe.put("/api/data", "{\"limits\":{\"daily\":500},\"expenses\":[]}").statusCode());

		AI.reply(FakeOpenAi.toolCall("c1", "check_affordability", "{\\\"amountInr\\\":2000,\\\"purpose\\\":\\\"shoes\\\"}"));
		AI.reply(FakeOpenAi.text("That is over your daily limit."));

		HttpResponse<String> r = zoe.post("/api/agent/chat", "{\"message\":\"Can I afford 2000 for shoes?\"}");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("That is over your daily limit."), r.body());
		assertTrue(r.body().contains("check_affordability"), r.body());

		assertEquals(2, AI.requests.size());

		/* The key is sent as a Bearer token, and the tools are described the OpenAI way. */
		assertEquals("Bearer free-test-key", AI.authorizations.get(0));
		assertTrue(AI.requests.get(0).contains("\"type\":\"function\""), AI.requests.get(0));
		assertTrue(AI.requests.get(0).contains("prepare_payment"), AI.requests.get(0));
		assertTrue(AI.requests.get(0).contains("free-model"), AI.requests.get(0));

		/* The second call carries the model's tool call and the tool's answer, worked out in Java. */
		String second = AI.requests.get(1);
		assertTrue(second.contains("\"role\":\"tool\""), second);
		assertTrue(second.contains("\"tool_call_id\":\"c1\""), second);
		assertTrue(second.contains("not_affordable"), second);
	}

	@Test
	void aPaymentIsOnlyPreparedNeverSent() throws Exception {
		AI.reply(FakeOpenAi.toolCall("c1", "prepare_payment",
				"{\\\"recipient\\\":\\\"9000000092\\\",\\\"amountInr\\\":800}"));
		AI.reply(FakeOpenAi.text("I prepared it. Please review and confirm."));

		HttpResponse<String> r = zoe.post("/api/agent/chat", "{\"message\":\"Pay 800 to 9000000092\"}");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("\"recipientName\":\"Ravi Free\""), r.body());
		assertEquals("[]", zoe.get("/api/payments").body());
	}

	@Test
	void theAiNoticeWhilePayingSeesLimitsLoansAndGoals() throws Exception {
		/* A payment that nearly uses up the daily limit, with an EMI due in two days and a savings goal. */
		assertEquals(200, zoe.put("/api/data", "{\"limits\":{\"daily\":500},"
				+ "\"loans\":[{\"name\":\"Bike EMI\",\"amount\":2000,\"due\":\"" + LocalDate.now().plusDays(2) + "\"}],"
				+ "\"goals\":[{\"name\":\"Laptop fund\",\"target\":50000,\"saved\":12000}]}").statusCode());

		AI.reply(FakeOpenAi.text("You are inside your limit."));

		/* Figures sent by the browser are ignored: the server uses the saved records. */
		HttpResponse<String> r = zoe.post("/api/guardian/explain",
				"{\"amountInr\":450,\"dailyLimit\":999999,\"recipientName\":\"Ravi Free\"}");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("You are inside your limit."), r.body());

		String sent = AI.requests.get(0);
		assertTrue(sent.contains("leaves only ₹50"), sent);
		assertTrue(sent.contains("Bike EMI"), sent);
		assertTrue(sent.contains("Laptop fund"), sent);
		assertTrue(sent.contains("Ravi Free"), sent);
		assertFalse(sent.contains("999999"), sent);
	}

	@Test
	void anAiReplyWithAnInventedNumberIsNeverShown() throws Exception {
		assertEquals(200, zoe.put("/api/data", "{\"limits\":{\"daily\":500}}").statusCode());

		/* The first reply invents a limit of 5,000. It is thrown away and the AI is asked once more. */
		AI.reply(FakeOpenAi.text("This is over your daily limit of ₹5,000."));
		AI.reply(FakeOpenAi.text("This fits your daily limit of ₹500 and leaves ₹400."));

		HttpResponse<String> ok = zoe.post("/api/guardian/explain", "{\"amountInr\":100}");

		assertEquals(200, ok.statusCode(), ok.body());
		assertTrue(ok.body().contains("leaves ₹400"), ok.body());
		assertEquals(2, AI.requests.size());

		/* Two wrong replies in a row: nothing from the AI is shown. The page keeps its rule-based advice. */
		AI.reply(FakeOpenAi.text("You have ₹9,999 left."));
		AI.reply(FakeOpenAi.text("You have ₹8,888 left."));

		HttpResponse<String> bad = zoe.post("/api/guardian/explain", "{\"amountInr\":100}");

		assertEquals(502, bad.statusCode(), bad.body());
		assertFalse(bad.body().contains("8,888"), bad.body());
	}
}
