package com.monetra;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenAiCompatibleClientTest {

	private static Map<String, Object> request(String baseUrl, String model) {
		Map<String, Object> request = new HashMap<>();
		request.put("max_tokens", 300);

		OpenAiCompatibleClient.reasoningSettings(request, baseUrl, model, 300);

		return request;
	}

	@Test
	void groqsReasoningModelThinksBrieflyAndHasRoomToAnswer() {
		Map<String, Object> groq = request("https://api.groq.com/openai/v1", "openai/gpt-oss-120b");

		assertEquals("low", groq.get("reasoning_effort"));
		assertEquals(false, groq.get("include_reasoning"));
		assertEquals(1324, groq.get("max_tokens"));
	}

	@Test
	void theSameModelElsewhereGetsOnlyTheStandardSetting() {
		Map<String, Object> other = request("https://openrouter.ai/api/v1", "openai/gpt-oss-20b");

		assertEquals("low", other.get("reasoning_effort"));
		assertFalse(other.containsKey("include_reasoning"));
	}

	@Test
	void otherModelsAreLeftAsTheyWere() {
		Map<String, Object> ollama = request("http://localhost:11434/v1", "llama3.2");

		assertEquals(300, ollama.get("max_tokens"));
		assertFalse(ollama.containsKey("reasoning_effort"));
		assertFalse(ollama.containsKey("include_reasoning"));
	}
}
