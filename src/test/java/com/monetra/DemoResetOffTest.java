package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real deployment has no demo accounts (monetra.seed-demo-users is off). Even if the reset switch were turned on
 * there by mistake, the reset does not exist: there is nothing that could run, and the server starts normally.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-demo-reset-off",
                "monetra.seed-demo-users=false",
                "monetra.demo-reset=true",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class DemoResetOffTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void withoutDemoAccountsTheResetDoesNotExistEvenIfSwitchedOn() throws Exception {
        assertTrue(context.getBeansOfType(DemoReset.class).isEmpty());
        assertTrue(context.getBeansOfType(DemoData.class).isEmpty());

        assertEquals(200, new Http(port).get("/api/payments/config").statusCode());
    }
}
