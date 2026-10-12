package com.monetra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** A tiny stand-in for an OpenAI-compatible chat API (Gemini, Groq, Ollama...). Replies are scripted by the test. */
final class FakeOpenAi {

    private final HttpServer server;
    private final ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();

    FakeOpenAi() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", this::handle);
            server.start();

        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The base URL a user would configure, including the usual /v1 part. */
    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    void stop() {
        server.stop(0);
    }

    void reset() {
        replies.clear();
        requests.clear();
        authorizations.clear();
    }

    void reply(String json) {
        replies.add(json);
    }

    /** A reply where the model calls one tool. */
    static String toolCall(String id, String name, String argumentsJsonEscaped) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                + "\",\"arguments\":\"" + argumentsJsonEscaped + "\"}}]}}]}";
    }

    /** A reply where the model just answers. */
    static String text(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        requests.add(body);
        authorizations.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));

        String reply = replies.poll();

        if (!ex.getRequestURI().getPath().equals("/v1/chat/completions") || reply == null) {
            ex.sendResponseHeaders(500, -1);
            ex.close();
            return;
        }

        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);

        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
