package com.monetra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the Anthropic Messages API. The API key stays on the server.
 * Replies are read as plain Maps and Lists, so this does not depend on a Jackson version.
 */
@Component
public class AnthropicClient implements AiProvider {

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${anthropic.model:claude-sonnet-5-5}")
    private String model;

    private final RestTemplate http = buildClient();

    private static RestTemplate buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    @Override
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String name() {
        return "Anthropic Claude";
    }

    @Override
    public String ask(String system, String userText, int maxTokens) {
        Ai.Step step = step(system, List.of(Ai.user(userText)), null, maxTokens);

        if (step.text().isEmpty()) {
            throw new IllegalStateException("Empty AI reply.");
        }

        return step.text();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Ai.Step step(String system, List<Ai.Turn> conversation, List<Map<String, Object>> tools, int maxTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("max_tokens", maxTokens);
        request.put("system", system);
        request.put("messages", wire(conversation));

        if (tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
        }

        Map<String, Object> reply = http.postForObject(
                baseUrl + "/v1/messages",
                new HttpEntity<>(request, headers),
                Map.class);

        if (reply == null) {
            throw new IllegalStateException("Empty AI reply.");
        }

        StringBuilder text = new StringBuilder();
        List<Ai.ToolCall> calls = new ArrayList<>();

        if (reply.get("content") instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (!(b instanceof Map<?, ?> block)) {
                    continue;
                }

                if ("text".equals(block.get("type")) && block.get("text") != null) {
                    text.append(block.get("text"));

                } else if ("tool_use".equals(block.get("type"))) {
                    Map<String, Object> input = block.get("input") instanceof Map<?, ?> in
                            ? (Map<String, Object>) in : new HashMap<>();

                    calls.add(new Ai.ToolCall(String.valueOf(block.get("id")), String.valueOf(block.get("name")), input));
                }
            }
        }

        return new Ai.Step(text.toString().trim(), calls);
    }

    /** Our neutral turns, as Anthropic wants them. */
    private static List<Map<String, Object>> wire(List<Ai.Turn> conversation) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (Ai.Turn t : conversation) {
            Map<String, Object> m = new LinkedHashMap<>();

            if ("assistant".equals(t.role())) {
                List<Map<String, Object>> blocks = new ArrayList<>();

                if (!t.text().isEmpty()) {
                    blocks.add(Map.of("type", "text", "text", t.text()));
                }

                for (Ai.ToolCall c : t.calls()) {
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("type", "tool_use");
                    use.put("id", c.id());
                    use.put("name", c.name());
                    use.put("input", c.input());
                    blocks.add(use);
                }

                m.put("role", "assistant");
                m.put("content", t.calls().isEmpty() ? t.text() : blocks);

            } else if ("tool".equals(t.role())) {
                List<Map<String, Object>> blocks = new ArrayList<>();

                for (Ai.ToolResult r : t.results()) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", r.callId());
                    block.put("content", r.content());

                    if (r.error()) {
                        block.put("is_error", true);
                    }

                    blocks.add(block);
                }

                m.put("role", "user");
                m.put("content", blocks);

            } else {
                m.put("role", "user");
                m.put("content", t.text());
            }

            out.add(m);
        }

        return out;
    }
}
