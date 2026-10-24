package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Try the demo" button, switched on: one press signs in the demo account with no password, nothing in the
 * request can choose another account, and the page is told to show the button.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-demo-login-on",
                "monetra.seed-demo-users=true",
                "monetra.demo-login=true",
                "monetra.demo-password=Test-Demo-Password-1",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class DemoLoginTest {

    @LocalServerPort
    int port;

    @Test
    void thePageIsToldToShowTheButton() throws Exception {
        String config = new Http(port).get("/api/payments/config").body();

        assertTrue(config.contains("\"demoLogin\":true"), config);
    }

    @Test
    void onePressSignsInTheDemoAccountWithoutAPassword() throws Exception {
        Http visitor = new Http(port);

        assertEquals(401, visitor.get("/api/auth/me").statusCode());

        assertEquals(200, visitor.post("/api/auth/demo", "").statusCode());

        HttpResponse<String> me = visitor.get("/api/auth/me");

        assertEquals(200, me.statusCode());
        assertEquals("demo1@monetra.test", Http.field(me.body(), "email"));

        /* A real signed-in session: private data opens, and signing out closes it again. */
        assertEquals(200, visitor.get("/api/data").statusCode());

        assertEquals(200, visitor.post("/api/auth/logout", "{}").statusCode());
        assertEquals(401, visitor.get("/api/auth/me").statusCode());
    }

    @Test
    void nothingInTheRequestCanChooseAnotherAccount() throws Exception {
        Http visitor = new Http(port);

        assertEquals(200, visitor.post("/api/auth/demo", "{\"email\":\"demo2@monetra.test\"}").statusCode());

        assertEquals("demo1@monetra.test", Http.field(visitor.get("/api/auth/me").body(), "email"));
    }

    @Test
    void theNormalSignInStillNeedsTheRightPassword() throws Exception {
        assertEquals(401, new Http(port).post("/api/auth/login",
                "{\"email\":\"demo1@monetra.test\",\"password\":\"not-the-password\"}").statusCode());

        assertEquals(200, new Http(port).post("/api/auth/login",
                "{\"email\":\"demo1@monetra.test\",\"password\":\"Test-Demo-Password-1\"}").statusCode());
    }
}
