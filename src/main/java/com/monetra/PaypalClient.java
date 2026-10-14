package com.monetra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to PayPal (Sandbox by default). Credentials stay on the server.
 * Replies are read as plain Maps and Lists, so this does not depend on a Jackson version.
 */
@Component
public class PaypalClient {

    @Value("${paypal.client-id:}")
    private String clientId;

    @Value("${paypal.client-secret:}")
    private String clientSecret;

    @Value("${paypal.base-url:https://api-m.sandbox.paypal.com}")
    private String baseUrl;

    @Value("${paypal.webhook-id:}")
    private String webhookId;

    private final RestTemplate http = buildClient();

    private static RestTemplate buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public boolean webhookConfigured() {
        return configured() && !webhookId.isBlank();
    }

    public String accessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(clientId, clientSecret);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        Map<String, Object> json = exchange("/v1/oauth2/token", HttpMethod.POST, new HttpEntity<>(form, headers));

        String token = str(json.get("access_token"));

        if (token.isEmpty()) {
            throw new IllegalStateException("PayPal did not return an access token.");
        }

        return token;
    }

    /** POST with a JSON body and a fresh access token. */
    public Map<String, Object> post(String path, Object body, String requestId) {
        HttpHeaders headers = bearer(accessToken());

        if (requestId != null) {
            headers.set("PayPal-Request-Id", requestId);
        }

        return exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers));
    }

    public Map<String, Object> get(String path) {
        return exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(accessToken())));
    }

    /** Pays {@code usd} dollars to a PayPal email through the Payouts API. */
    public Map<String, Object> createPayout(String senderBatchId, String email, BigDecimal usd, String note) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("sender_batch_id", senderBatchId);
        header.put("email_subject", "You received money on Monetra");

        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("value", usd.toPlainString());
        amount.put("currency", "USD");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("recipient_type", "EMAIL");
        item.put("receiver", email);
        item.put("amount", amount);
        item.put("note", note);
        item.put("sender_item_id", senderBatchId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender_batch_header", header);
        body.put("items", Collections.singletonList(item));

        return post("/v1/payments/payouts", body, senderBatchId + "-payout");
    }

    /** Asks PayPal whether a webhook really came from PayPal. */
    public boolean verifyWebhook(Map<String, String> headers, Map<String, Object> event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth_algo", header(headers, "paypal-auth-algo"));
        body.put("cert_url", header(headers, "paypal-cert-url"));
        body.put("transmission_id", header(headers, "paypal-transmission-id"));
        body.put("transmission_sig", header(headers, "paypal-transmission-sig"));
        body.put("transmission_time", header(headers, "paypal-transmission-time"));
        body.put("webhook_id", webhookId);
        body.put("webhook_event", event);

        Map<String, Object> reply = post("/v1/notifications/verify-webhook-signature", body, null);

        return "SUCCESS".equals(str(reply.get("verification_status")));
    }

    /* ------------------------------------------------------------------ */

    private Map<String, Object> exchange(String path, HttpMethod method, HttpEntity<?> entity) {
        return read(http.exchange(baseUrl + path, method, entity, Map.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(ResponseEntity<?> response) {
        Map<String, Object> body = response.getBody() instanceof Map<?, ?> m ? (Map<String, Object>) m : null;

        if (body == null) {
            throw new IllegalStateException("Empty response from PayPal.");
        }

        return body;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }

        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * A short, safe reason from a PayPal error reply, for example
     * "INSTRUMENT_DECLINED: The instrument presented was declined" or "invalid_client: Client Authentication failed".
     * It never contains credentials. Shown to the user so they can see why PayPal said no.
     */
    public static String errorSummary(org.springframework.web.client.HttpStatusCodeException e) {
        String fallback = "HTTP " + e.getStatusCode().value();

        try {
            com.fasterxml.jackson.databind.JsonNode n =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString());

            String code = n.path("details").path(0).path("issue").asText("");

            if (code.isEmpty()) {
                code = n.path("name").asText("");
            }

            if (code.isEmpty()) {
                code = n.path("error").asText("");
            }

            String text = n.path("details").path(0).path("description").asText("");

            if (text.isEmpty()) {
                text = n.path("error_description").asText("");
            }

            if (text.isEmpty()) {
                text = n.path("message").asText("");
            }

            String out = (code.isEmpty() ? fallback : code) + (text.isEmpty() ? "" : ": " + text);

            return out.length() > 240 ? out.substring(0, 240) : out;

        } catch (Exception ignored) {
            return fallback;
        }
    }

    /* Small helpers shared with callers that read PayPal replies. */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new HashMap<String, Object>();
    }

    public static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    public static Object first(List<?> items) {
        return items.isEmpty() ? null : items.get(0);
    }

    public static String text(Object value) {
        return str(value);
    }
}
