package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Too many presses of the demo button from one address are paused for a while (it needs its own server). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-demo-login-limit",
                "monetra.seed-demo-users=true",
                "monetra.demo-login=true",
                "monetra.demo-password=Test-Demo-Password-3",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class DemoLoginLimitTest {

    @LocalServerPort
    int port;

    @Test
    void thirtyPressesAreAllowedAndTheNextOneIsPaused() throws Exception {
        Http visitor = new Http(port);

        for (int i = 0; i < 30; i++) {
            assertEquals(200, visitor.post("/api/auth/demo", "").statusCode(), "press " + (i + 1));
        }

        assertEquals(429, visitor.post("/api/auth/demo", "").statusCode());
    }
}
