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
	void explainEndpointsUseTheFreeProviderToo() throws Exception {
		AI.reply(FakeOpenAi.text("You are inside your limit."));

		HttpResponse<String> r = zoe.post("/api/guardian/explain",
				"{\"amountInr\":100,\"dailyLimit\":500,\"spentToday\":0,\"remainingAfter\":400,\"level\":\"ok\"}");

		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.body().contains("You are inside your limit."), r.body());
	}
}
