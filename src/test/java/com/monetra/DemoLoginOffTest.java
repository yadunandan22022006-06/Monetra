package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Try the demo" button is OFF unless someone switches it on: with the demo accounts present but the switch
 * left alone (the default), the button is not offered and the address refuses, so the passwords still protect them.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-demo-login-off",
                "monetra.seed-demo-users=true",
                "monetra.demo-password=Test-Demo-Password-2",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class DemoLoginOffTest {

    @LocalServerPort
    int port;

    @Test
    void withTheSwitchOffTheButtonIsNotOfferedAndTheAddressRefuses() throws Exception {
        Http visitor = new Http(port);

        assertTrue(visitor.get("/api/payments/config").body().contains("\"demoLogin\":false"));

        assertEquals(404, visitor.post("/api/auth/demo", "").statusCode());

        assertEquals(401, visitor.get("/api/auth/me").statusCode());
        assertEquals(401, visitor.get("/api/data").statusCode());
    }

    @Test
    void thePasswordSignInIsUnchanged() throws Exception {
        assertEquals(200, new Http(port).post("/api/auth/login",
                "{\"email\":\"demo1@monetra.test\",\"password\":\"Test-Demo-Password-2\"}").statusCode());

        assertFalse(new Http(port).post("/api/auth/login",
                "{\"email\":\"demo1@monetra.test\",\"password\":\"wrong\"}").statusCode() == 200);
    }
}
