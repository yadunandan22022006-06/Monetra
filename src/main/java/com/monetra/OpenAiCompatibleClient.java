package com.monetra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to any "OpenAI-compatible" chat API. That covers free options such as
 * Groq (free tier, for example openai/gpt-oss-120b), Google Gemini (free tier), OpenRouter free models
 * and Ollama running on this computer.
 *
 * Set AI_BASE_URL, AI_MODEL and (except for a local Ollama) AI_API_KEY. See the README for examples.
 *
 * AI_FALLBACK_BASE_URL and AI_FALLBACK_MODEL can name an AI on this computer (Ollama). It is asked only
 * when the online AI cannot be reached, so a dropped connection does not stop the AI features.
 */
@Component
public class OpenAiCompatibleClient implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    @Value("${ai.base-url:}")
    private String baseUrl;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.model:}")
    private String model;

    /** The AI on this computer that answers when the online one cannot be reached. Empty: none. */
    @Value("${ai.fallback-base-url:}")
    private String fallbackUrl;

    @Value("${ai.fallback-model:}")
    private String fallbackModel;

    private final ObjectMapper mapper = new ObjectMapper();

    private final RestTemplate http = buildClient();

    private static RestTemplate buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);   /* a model on your own computer can be slow to load */
        return new RestTemplate(factory);
    }

    @Override
    public boolean configured() {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            return false;
        }

        return local() || (apiKey != null && !apiKey.isBlank());
    }

    /** True when the AI runs on this computer (for example Ollama), so it needs no key and costs nothing to call. */
    public boolean local() {
        return baseUrl != null && (baseUrl.contains("://localhost") || baseUrl.contains("://127.0.0.1"));
    }

    @Override
    public String name() {
        return "OpenAI-compatible (" + model + ")";
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
    public Ai.Step step(String system, List<Ai.Turn> conversation, List<Map<String, Object>> tools, int maxTokens) {
        try {
            return step(baseUrl, model, apiKey, system, conversation, tools, maxTokens);

        } catch (ResourceAccessException e) {
            /* The online AI could not be reached (no connection, a dropped handshake, a timeout). */
            if (!fallbackReady()) {
                throw e;
            }

            log.warn("The online AI could not be reached ({}); the AI on this computer answers instead", e.getMessage());

            return step(fallbackUrl, fallbackModel, null, system, conversation, tools, maxTokens);
        }
    }

    /** True when a different AI on this computer is set up to answer instead. */
    boolean fallbackReady() {
        return fallbackUrl != null && !fallbackUrl.isBlank() && fallbackModel != null && !fallbackModel.isBlank()
                && !fallbackUrl.equals(baseUrl);
    }

    @SuppressWarnings("unchecked")
    private Ai.Step step(String toUrl, String toModel, String key, String system, List<Ai.Turn> conversation,
                         List<Map<String, Object>> tools, int maxTokens) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (key != null && !key.isBlank()) {
            headers.setBearerAuth(key);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.addAll(wire(conversation));

        Map<String, Object> request = new HashMap<>();
        request.put("model", toModel);
        request.put("max_tokens", maxTokens);
        request.put("temperature", 0.2);   /* stay close to the facts */
        request.put("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            request.put("tools", wireTools(tools));
        }

        reasoningSettings(request, toUrl, toModel, maxTokens);

        String url = toUrl.endsWith("/") ? toUrl + "chat/completions" : toUrl + "/chat/completions";

        Map<String, Object> reply = http.postForObject(url, new HttpEntity<>(request, headers), Map.class);

        Map<String, Object> message = new HashMap<>();

        if (reply != null && reply.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice && choice.get("message") instanceof Map<?, ?> m) {

            message = (Map<String, Object>) m;
        }

        String text = message.get("content") == null ? "" : String.valueOf(message.get("content")).trim();

        List<Ai.ToolCall> calls = new ArrayList<>();

        if (message.get("tool_calls") instanceof List<?> raw) {
            int n = 0;

            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> call) || !(call.get("function") instanceof Map<?, ?> fn)) {
                    continue;
                }

                Map<String, Object> input = new HashMap<>();

                Object args = fn.get("arguments");

                if (args instanceof String s && !s.isBlank()) {
                    try {
                        input = mapper.readValue(s, Map.class);
                    } catch (Exception e) {
                        input = new HashMap<>();
                    }

                } else if (args instanceof Map<?, ?> a) {
                    input = (Map<String, Object>) a;
                }

                String id = call.get("id") == null ? "call_" + (n++) : String.valueOf(call.get("id"));

                calls.add(new Ai.ToolCall(id, String.valueOf(fn.get("name")), input));
            }
        }

        return new Ai.Step(text, calls);
    }

    /**
     * Reasoning models such as OpenAI's gpt-oss (which Groq serves) think before they answer, and that
     * thinking uses the same token budget as the answer. Monetra only wants a short answer, so these
     * models are asked to think briefly and are given room for it. On Groq the thinking itself is not
     * sent back. Other models are left exactly as they were.
     */
    static void reasoningSettings(Map<String, Object> request, String baseUrl, String model, int maxTokens) {
        if (model == null || !model.toLowerCase().contains("gpt-oss")) {
            return;
        }

        request.put("reasoning_effort", "low");
        request.put("max_tokens", maxTokens + 1024);

        if (baseUrl != null && baseUrl.contains("api.groq.com")) {
            request.put("include_reasoning", false);
        }
    }

    /** Our neutral turns, as the OpenAI chat format wants them. */
    private List<Map<String, Object>> wire(List<Ai.Turn> conversation) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (Ai.Turn t : conversation) {
            if ("assistant".equals(t.role())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", t.text().isEmpty() ? null : t.text());

                if (!t.calls().isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();

                    for (Ai.ToolCall c : t.calls()) {
                        Map<String, Object> fn = new LinkedHashMap<>();
                        fn.put("name", c.name());
                        fn.put("arguments", json(c.input()));

                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("id", c.id());
                        call.put("type", "function");
                        call.put("function", fn);

                        calls.add(call);
                    }

                    m.put("tool_calls", calls);
                }

                out.add(m);

            } else if ("tool".equals(t.role())) {
                for (Ai.ToolResult r : t.results()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "tool");
                    m.put("tool_call_id", r.callId());
                    m.put("name", r.name());
                    m.put("content", r.error() ? "ERROR: " + r.content() : r.content());
                    out.add(m);
                }

            } else {
                out.add(Map.of("role", "user", "content", t.text()));
            }
        }

        return out;
    }

    /** Our tool descriptions (Anthropic style) as OpenAI function tools. */
    private static List<Map<String, Object>> wireTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.get("name"));
            fn.put("description", t.get("description"));
            fn.put("parameters", t.get("input_schema"));

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);

            out.add(tool);
        }

        return out;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
