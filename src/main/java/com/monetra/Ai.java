package com.monetra;

import java.util.List;
import java.util.Map;

/** Provider-neutral building blocks of an AI conversation with tools. */
public final class Ai {

    private Ai() {
    }

    /** The model asks to run one tool. */
    public record ToolCall(String id, String name, Map<String, Object> input) {
    }

    /** The answer to one tool call. */
    public record ToolResult(String callId, String name, String content, boolean error) {
    }

    /**
     * One turn of the conversation. role is "user", "assistant" or "tool".
     * An assistant turn may carry tool calls. A tool turn carries the results.
     */
    public record Turn(String role, String text, List<ToolCall> calls, List<ToolResult> results) {
    }

    /** What the model said in one step: some text and/or tool calls. */
    public record Step(String text, List<ToolCall> calls) {
    }

    public static Turn user(String text) {
        return new Turn("user", text, List.of(), List.of());
    }

    public static Turn assistant(String text, List<ToolCall> calls) {
        return new Turn("assistant", text, calls, List.of());
    }

    public static Turn tool(List<ToolResult> results) {
        return new Turn("tool", "", List.of(), results);
    }
}
