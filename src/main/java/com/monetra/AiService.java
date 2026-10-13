package com.monetra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The AI Monetra uses. Pick one with AI_PROVIDER, or leave it empty to choose automatically:
 *  - "anthropic": Claude (needs ANTHROPIC_API_KEY, paid)
 *  - "openai": any OpenAI-compatible API, including free ones (needs AI_BASE_URL and AI_MODEL, and AI_API_KEY unless it runs on this computer)
 * Automatic choice: Claude if its key is set, otherwise the OpenAI-compatible one if it is set up.
 */
@Service
public class AiService implements AiProvider {

    private final AnthropicClient anthropic;
    private final OpenAiCompatibleClient openai;
    private final String choice;

    public AiService(
            AnthropicClient anthropic,
            OpenAiCompatibleClient openai,
            @Value("${ai.provider:}") String choice) {

        this.anthropic = anthropic;
        this.openai = openai;
        this.choice = choice == null ? "" : choice.trim().toLowerCase();
    }

    private AiProvider active() {
        if ("anthropic".equals(choice)) {
            return anthropic;
        }

        if ("openai".equals(choice) || "free".equals(choice)) {
            return openai;
        }

        return anthropic.configured() ? anthropic : openai;
    }

    @Override
    public boolean configured() {
        return active().configured();
    }

    /** True when the AI in use runs on this computer, so waking it up early is free. */
    public boolean runsLocally() {
        return active() == openai && openai.configured() && openai.local();
    }

    @Override
    public String name() {
        return active().name();
    }

    @Override
    public String ask(String system, String userText, int maxTokens) {
        return active().ask(system, userText, maxTokens);
    }

    @Override
    public Ai.Step step(String system, List<Ai.Turn> conversation, List<Map<String, Object>> tools, int maxTokens) {
        return active().step(system, conversation, tools, maxTokens);
    }
}
