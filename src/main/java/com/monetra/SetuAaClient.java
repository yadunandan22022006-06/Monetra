package com.monetra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to Setu's Account Aggregator gateway as an FIU (Financial Information User), following
 * Setu's AA v2 API and its Postman collection:
 *
 *   login     POST {token-url}                 header "client: bridge", body clientID, grant_type, secret
 *   consents  POST /v2/consents, GET /v2/consents/{id}, POST /v2/consents/{id}/revoke
 *   data      POST /v2/sessions,  GET /v2/sessions/{id}
 *
 * Every call carries "Authorization: Bearer <token>" and "x-product-instance-id". The keys stay on
 * the server. Sandbox by default: Setu's sandbox answers with test accounts, never with a real bank.
 */
@Component
public class SetuAaClient {

    @Value("${setu.aa.client-id:}")
    private String clientId;

    @Value("${setu.aa.client-secret:}")
    private String clientSecret;

    @Value("${setu.aa.product-instance-id:}")
    private String productInstanceId;

    @Value("${setu.aa.base-url:https://fiu-sandbox.setu.co}")
    private String baseUrl;

    @Value("${setu.aa.token-url:https://orgservice-prod.setu.co/v1/users/login}")
    private String tokenUrl;

    private final RestTemplate http = buildClient();

    /** Setu's token lasts 300 seconds; it is reused until shortly before then. */
    private String token = "";
    private Instant tokenUntil = Instant.EPOCH;

    private static RestTemplate buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    public boolean configured() {
        return missing().isEmpty();
    }

    /** The settings that still have to be filled in, by the names the start script uses. */
    public List<String> missing() {
        List<String> out = new ArrayList<>();

        if (clientId.isBlank()) {
            out.add("SETU_AA_CLIENT_ID");
        }

        if (clientSecret.isBlank()) {
            out.add("SETU_AA_CLIENT_SECRET");
        }

        if (productInstanceId.isBlank()) {
            out.add("SETU_AA_PRODUCT_INSTANCE_ID");
        }

        return out;
    }

    /** True unless the server is pointed at Setu's production gateway. */
    public boolean sandbox() {
        return !baseUrl.contains("://fiu.setu.co");
    }

    /** Creates a consent request. The reply has the consent's "id" and the "url" of Setu's consent screens. */
    public Map<String, Object> createConsent(String vua, String redirectUrl, Instant from, Instant to) {
        Map<String, Object> duration = new LinkedHashMap<>();
        duration.put("unit", "MONTH");
        duration.put("value", "12");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consentDuration", duration);
        body.put("vua", vua);
        body.put("dataRange", range(from, to));
        body.put("context", List.of());
        body.put("redirectUrl", redirectUrl);

        return call(HttpMethod.POST, "/v2/consents", body);
    }

    /** The consent's status: PENDING, ACTIVE, REJECTED, REVOKED, PAUSED or EXPIRED. */
    public Map<String, Object> getConsent(String consentId) {
        return call(HttpMethod.GET, "/v2/consents/" + consentId, null);
    }

    public Map<String, Object> revokeConsent(String consentId) {
        return call(HttpMethod.POST, "/v2/consents/" + consentId + "/revoke", null);
    }

    /** Asks the bank (FIP) for the accounts' data under an approved consent. */
    public Map<String, Object> createSession(String consentId, Instant from, Instant to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consentId", consentId);
        body.put("dataRange", range(from, to));
        body.put("format", "json");

        return call(HttpMethod.POST, "/v2/sessions", body);
    }

    /** The data session: PENDING, PARTIAL, COMPLETED, EXPIRED or FAILED, with each account's data once ready. */
    public Map<String, Object> getSession(String sessionId) {
        return call(HttpMethod.GET, "/v2/sessions/" + sessionId, null);
    }

    /* ------------------------------------------------------------------ */

    private Map<String, Object> call(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        headers.set("x-product-instance-id", productInstanceId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            return read(http.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), Map.class));

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 401) {
                /* The token may have run out early: the next call logs in again. */
                tokenUntil = Instant.EPOCH;
            }

            throw e;
        }
    }

    private synchronized String token() {
        if (!token.isEmpty() && Instant.now().isBefore(tokenUntil)) {
            return token;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("client", "bridge");
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientID", clientId);
        body.put("grant_type", "client_credentials");
        body.put("secret", clientSecret);

        Map<String, Object> json = read(http.exchange(tokenUrl, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class));

        String fresh = json.get("access_token") == null ? "" : String.valueOf(json.get("access_token"));

        if (fresh.isEmpty()) {
            throw new IllegalStateException("Setu did not return an access token. Check SETU_AA_CLIENT_ID and SETU_AA_CLIENT_SECRET.");
        }

        token = fresh;
        tokenUntil = Instant.now().plusSeconds(240);

        return token;
    }

    private static Map<String, Object> range(Instant from, Instant to) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", from.toString());
        range.put("to", to.toString());
        return range;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(ResponseEntity<?> response) {
        Map<String, Object> body = response.getBody() instanceof Map<?, ?> m ? (Map<String, Object>) m : null;

        return body == null ? new HashMap<>() : body;
    }

    /** Setu's own words for what went wrong, in one line, without anything secret. */
    public static String errorSummary(HttpStatusCodeException e) {
        String text = e.getResponseBodyAsString();

        for (String key : new String[] {"errorMsg", "message", "error", "errorCode"}) {
            int at = text.indexOf("\"" + key + "\"");

            if (at >= 0) {
                int start = text.indexOf('"', text.indexOf(':', at) + 1);
                int end = start < 0 ? -1 : text.indexOf('"', start + 1);

                if (start >= 0 && end > start) {
                    return text.substring(start + 1, Math.min(end, start + 1 + 200));
                }
            }
        }

        return "HTTP " + e.getStatusCode().value();
    }
}
