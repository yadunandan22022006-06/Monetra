package com.monetra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** A tiny stand-in for the Anthropic Messages API. Replies are scripted by the test. */
final class FakeAnthropic {

    private final HttpServer server;
    private final ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>();
    final List<String> requests = new CopyOnWriteArrayList<>();

    FakeAnthropic() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", this::handle);
            server.start();

        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    void reset() {
        replies.clear();
        requests.clear();
    }

    /** The next reply, as the JSON the real API would send. */
    void reply(String json) {
        replies.add(json);
    }

    /** A reply where the model calls one tool. */
    static String toolUse(String id, String name, String inputJson) {
        return "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"text\",\"text\":\"Let me check.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}]}";
    }

    /** A reply where the model just answers. */
    static String text(String text) {
        return "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        requests.add(body);

        String reply = replies.poll();

        if (!ex.getRequestURI().getPath().equals("/v1/messages") || reply == null) {
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
