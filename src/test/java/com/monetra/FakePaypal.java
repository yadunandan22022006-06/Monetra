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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A tiny stand-in for the PayPal Sandbox, running on this machine, so tests can run the whole payment journey. */
final class FakePaypal {

    private final HttpServer server;
    private final Map<String, String> orderValues = new ConcurrentHashMap<>();
    private final AtomicInteger orders = new AtomicInteger();
    final List<String> payoutReceivers = new CopyOnWriteArrayList<>();

    /** What Monetra sent each time it created an order. */
    final List<String> orderRequests = new CopyOnWriteArrayList<>();

    FakePaypal() {
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

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String reply;

        if (path.equals("/v1/oauth2/token")) {
            reply = "{\"access_token\":\"fake-token\"}";

        } else if (path.equals("/v2/checkout/orders")) {
            String id = "ORDER" + orders.incrementAndGet();

            orderRequests.add(body);

            Matcher m = Pattern.compile("\"value\":\"([0-9.]+)\"").matcher(body);

            orderValues.put(id, m.find() ? m.group(1) : "0.00");

            reply = "{\"id\":\"" + id + "\",\"links\":[{\"rel\":\"payer-action\","
                    + "\"href\":\"https://www.sandbox.paypal.com/checkoutnow?token=" + id + "\"}]}";

        } else if (path.matches("/v2/checkout/orders/[A-Z0-9]+/capture")) {
            String id = path.split("/")[4];

            reply = "{\"status\":\"COMPLETED\",\"purchase_units\":[{\"payments\":{\"captures\":["
                    + "{\"id\":\"CAP-" + id + "\",\"status\":\"COMPLETED\",\"amount\":"
                    + "{\"currency_code\":\"USD\",\"value\":\"" + orderValues.get(id) + "\"}}]}}]}";

        } else if (path.matches("/v2/checkout/orders/[A-Z0-9]+")) {
            reply = "{\"status\":\"APPROVED\"}";

        } else if (path.equals("/v1/payments/payouts")) {
            Matcher m = Pattern.compile("\"receiver\":\"([^\"]+)\"").matcher(body);

            if (m.find()) {
                payoutReceivers.add(m.group(1));
            }

            reply = "{\"batch_header\":{\"payout_batch_id\":\"BATCH" + payoutReceivers.size()
                    + "\",\"batch_status\":\"PENDING\"}}";

        } else if (path.equals("/v1/notifications/verify-webhook-signature")) {
            reply = body.contains("\"transmission_id\":\"good\"")
                    ? "{\"verification_status\":\"SUCCESS\"}"
                    : "{\"verification_status\":\"FAILURE\"}";

        } else {
            ex.sendResponseHeaders(404, -1);
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
