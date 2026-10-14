package com.monetra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny stand-in for Setu's Account Aggregator gateway, answering the calls Monetra makes the way
 * Setu's API documents them, so tests can run linking and Check Balance end to end.
 */
final class FakeSetu {

    private final HttpServer server;

    private final AtomicInteger consents = new AtomicInteger();
    private final AtomicInteger sessions = new AtomicInteger();

    /** What Monetra sent. */
    final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    final List<String> consentRequests = new CopyOnWriteArrayList<>();
    final List<String> revoked = new CopyOnWriteArrayList<>();
    final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

    /** What the user did on Setu's screens, by consent id: ACTIVE, REJECTED or PENDING. */
    final Map<String, String> consentStatus = new ConcurrentHashMap<>();

    /** How the next data sessions go: COMPLETED, PARTIAL, PENDING, FAILED, or ERROR (an HTTP 500). */
    volatile String sessionMode = "COMPLETED";

    volatile String balance = "101666.33";

    FakeSetu() {
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

    int sessionsCreated() {
        return sessions.get();
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (path.equals("/login")) {
            tokenRequests.add("client=" + ex.getRequestHeaders().getFirst("client") + " " + body);
            reply(ex, 200, "{\"access_token\":\"setu-token\",\"expiresIn\":300}");
            return;
        }

        /* Every gateway call must carry the token and the product instance id. */
        lastHeaders.put("authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        lastHeaders.put("x-product-instance-id", String.valueOf(ex.getRequestHeaders().getFirst("x-product-instance-id")));

        if (!"Bearer setu-token".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            reply(ex, 401, "{\"errorCode\":\"Unauthorized\",\"errorMsg\":\"Invalid token\"}");
            return;
        }

        if (method.equals("POST") && path.equals("/v2/consents")) {
            String id = "consent-" + consents.incrementAndGet();

            consentRequests.add(body);
            consentStatus.put(id, "PENDING");

            reply(ex, 201, "{\"id\":\"" + id + "\",\"url\":\"https://fiu-sandbox.setu.co/consents/webview/" + id
                    + "\",\"status\":\"PENDING\"}");

        } else if (method.equals("POST") && path.matches("/v2/consents/[^/]+/revoke")) {
            String id = path.split("/")[3];

            revoked.add(id);
            consentStatus.put(id, "REVOKED");

            reply(ex, 200, "{\"status\":\"REVOKED\"}");

        } else if (method.equals("GET") && path.startsWith("/v2/consents/")) {
            String id = path.substring("/v2/consents/".length());
            String status = consentStatus.getOrDefault(id, "PENDING");

            String linked = "ACTIVE".equals(status)
                    ? "[{\"maskedAccNumber\":\"XXXXXXXX1234\",\"linkRefNumber\":\"ref-" + id + "\",\"fipId\":\"setu-fip\",\"accType\":\"SAVINGS\"}]"
                    : "[]";

            reply(ex, 200, "{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"accountsLinked\":" + linked + "}");

        } else if (method.equals("POST") && path.equals("/v2/sessions")) {
            if ("ERROR".equals(sessionMode)) {
                reply(ex, 500, "{\"errorCode\":\"InternalError\",\"errorMsg\":\"FIP not reachable\"}");
                return;
            }

            String consentId = body.replaceAll(".*\"consentId\":\"([^\"]+)\".*", "$1");

            reply(ex, 201, "{\"id\":\"session-" + sessions.incrementAndGet() + "-" + consentId + "\",\"status\":\"PENDING\"}");

        } else if (method.equals("GET") && path.startsWith("/v2/sessions/")) {
            String id = path.substring("/v2/sessions/".length());
            String consentId = id.replaceFirst("^session-\\d+-", "");

            String status = switch (sessionMode) {
                case "PENDING" -> "PENDING";
                case "FAILED" -> "FAILED";
                case "PARTIAL" -> "PARTIAL";
                default -> "COMPLETED";
            };

            String accounts = "COMPLETED".equals(status) || "PARTIAL".equals(status)
                    ? "[{\"linkRefNumber\":\"ref-" + consentId + "\",\"maskedAccNumber\":\"XXXXXXXX1234\",\"FIstatus\":\"READY\","
                            + "\"data\":{\"account\":{\"type\":\"deposit\",\"maskedAccNumber\":\"XXXXXXXX1234\","
                            + "\"profile\":{\"holders\":{\"type\":\"SINGLE\"}},"
                            + "\"summary\":{\"currentBalance\":\"" + balance + "\",\"currency\":\"INR\","
                            + "\"balanceDateTime\":\"2026-10-14T10:00:00+05:30\",\"type\":\"SAVINGS\"},"
                            + "\"transactions\":{\"transaction\":[]}}}}]"
                    : "[]";

            reply(ex, 200, "{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"format\":\"json\","
                    + "\"fips\":[{\"fipID\":\"setu-fip\",\"accounts\":" + accounts + "}]}");

        } else {
            reply(ex, 404, "{\"errorMsg\":\"not found\"}");
        }
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
