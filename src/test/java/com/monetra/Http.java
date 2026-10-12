package com.monetra;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small test client that keeps the login cookie, like a browser. */
final class Http {

    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final int port;

    Http(int port) {
        this.port = port;
    }

    static Http login(int port, String email, String password) throws Exception {
        Http h = new Http(port);

        HttpResponse<String> r = h.post("/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");

        if (r.statusCode() != 200) {
            throw new IllegalStateException("Login failed for " + email + ": " + r.statusCode());
        }

        return h;
    }

    HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, Map.of());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body, Map.of());
    }

    HttpResponse<String> put(String path, String body) throws Exception {
        return send("PUT", path, body, Map.of());
    }

    HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null, Map.of());
    }

    HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");

        headers.forEach(b::header);

        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));

        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The first "name":"value" string in a JSON text. */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);

        return m.find() ? m.group(1) : "";
    }

    /** The first "name":123 number in a JSON text. */
    static String number(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":([0-9.]+)").matcher(json);

        return m.find() ? m.group(1) : "";
    }
}
