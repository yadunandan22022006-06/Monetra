package com.monetra;

import java.util.List;
import java.util.Map;

/** Something that can answer a question and run a tool-calling conversation. */
public interface AiProvider {

    /** Is this provider set up (key, address, model)? */
    boolean configured();

    /** A short name for logs and the settings screen. */
    String name();

    /** One short question with no tools. Returns the reply text. */
    default String ask(String system, String userText, int maxTokens) {
        Ai.Step step = step(system, List.of(Ai.user(userText)), null, maxTokens);

        if (step.text().isEmpty()) {
            throw new IllegalStateException("Empty AI reply.");
        }

        return step.text();
    }

    /** One step of a conversation where the model may call tools. */
    Ai.Step step(String system, List<Ai.Turn> conversation, List<Map<String, Object>> tools, int maxTokens);
}
