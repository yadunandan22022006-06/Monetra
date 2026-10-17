package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser protection headers are on the page, the scripts and every answer of the API, errors included. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-security-headers",
                "spring.mail.host=localhost",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class SecurityHeadersTest {

    @LocalServerPort
    int port;

    private static void protectedResponse(HttpResponse<String> res, String what) {
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").orElse(""), what);
        assertEquals("DENY", res.headers().firstValue("X-Frame-Options").orElse(""), what);
        assertEquals("strict-origin-when-cross-origin", res.headers().firstValue("Referrer-Policy").orElse(""), what);
        assertTrue(res.headers().firstValue("Permissions-Policy").orElse("").contains("camera=(self)"), what);
    }

    @Test
    void thePageTheScriptsAndTheApiAreAllProtected() throws Exception {
        Http http = new Http(port);

        protectedResponse(http.get("/"), "the page");
        protectedResponse(http.get("/app.js"), "the script");
        protectedResponse(http.get("/app.css"), "the styles");
        protectedResponse(http.get("/api/payments/config"), "a public API answer");
        protectedResponse(http.get("/api/data"), "a refused API answer (401)");
        protectedResponse(http.get("/api/does-not-exist"), "a missing address (404)");
    }

    @Test
    void aFailedSignInSetsNoCookie() throws Exception {
        HttpResponse<String> res = new Http(port).post("/api/auth/login",
                "{\"email\":\"nobody@test.dev\",\"password\":\"not-a-real-password\"}");

        assertEquals(401, res.statusCode());
        assertTrue(res.headers().firstValue("Set-Cookie").isEmpty(), "no session is created for a failed sign-in");
    }
}
