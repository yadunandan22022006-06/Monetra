package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The demo switch is on, but there is no demo account on this server: nothing is shown, and the address refuses. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-demo-login-noaccount",
                "monetra.seed-demo-users=false",
                "monetra.demo-login=true",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class DemoLoginNoAccountTest {

    @LocalServerPort
    int port;

    @Test
    void aSwitchWithNoDemoAccountShowsNothingAndRefuses() throws Exception {
        Http visitor = new Http(port);

        assertTrue(visitor.get("/api/payments/config").body().contains("\"demoLogin\":false"));

        assertEquals(404, visitor.post("/api/auth/demo", "").statusCode());
        assertEquals(401, visitor.get("/api/auth/me").statusCode());
    }
}
