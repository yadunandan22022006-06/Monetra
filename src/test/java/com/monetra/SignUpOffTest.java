package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On a website where email is not set up (the online demo), asking to sign up gives a plain sentence for
 * visitors: no server settings, no variable names, and no account or code is created.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-signup-off",
                "spring.mail.host=localhost",
                "spring.mail.username=",
                "monetra.log-otp=false",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class SignUpOffTest {

    @LocalServerPort
    int port;

    @Test
    void signUpIsPolitelyRefusedWithoutRevealingServerSettings() throws Exception {
        Http http = new Http(port);

        HttpResponse<String> res = http.post("/api/auth/send-otp",
                "{\"email\":\"visitor@test.dev\",\"password\":\"long-enough-1\",\"phone\":\"9000000081\",\"name\":\"Visitor\"}");

        assertEquals(503, res.statusCode());
        assertTrue(res.body().contains("switched off"), res.body());
        assertTrue(res.body().contains("demo account"), res.body());

        String lower = res.body().toLowerCase();

        assertFalse(lower.contains("monetra_mail") || lower.contains("gmail") || lower.contains("app password"),
                "no server settings in the message: " + res.body());
    }

    @Test
    void nothingIsCreatedWhenSignUpIsRefused() throws Exception {
        Http http = new Http(port);

        http.post("/api/auth/send-otp",
                "{\"email\":\"visitor2@test.dev\",\"password\":\"long-enough-1\",\"phone\":\"9000000082\",\"name\":\"Visitor\"}");

        /* No code was kept, so no code can be checked and the person still cannot sign in. */
        assertEquals(400, http.post("/api/auth/verify-otp", "{\"email\":\"visitor2@test.dev\",\"otp\":\"123456\"}").statusCode());
        assertEquals(401, new Http(port).post("/api/auth/login", "{\"email\":\"visitor2@test.dev\",\"password\":\"long-enough-1\"}").statusCode());
    }
}
