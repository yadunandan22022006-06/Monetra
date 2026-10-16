package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guessing passwords or asking for sign-up codes over and over is paused, without locking out other people. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-signin-limit",
                "spring.mail.host=localhost",
                "spring.mail.username=",
                "monetra.log-otp=true",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class SignInLimitTest {

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository users;

    private void user(String email, String phone) {
        users.save(new UserAccount(email, new BCryptPasswordEncoder().encode("right-password-1"), phone, email));
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return new Http(port).post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    void eightWrongPasswordsPauseThatEmailEvenForTheRightPasswordButNobodyElse() throws Exception {
        user("limit1@test.dev", "9000000051");
        user("limit2@test.dev", "9000000052");

        for (int i = 0; i < 8; i++) {
            assertEquals(401, login("limit1@test.dev", "wrong-" + i).statusCode(), "wrong password " + (i + 1));
        }

        HttpResponse<String> paused = login("limit1@test.dev", "right-password-1");

        assertEquals(429, paused.statusCode());
        assertTrue(paused.body().contains("wait 15 minutes"), paused.body());

        /* Someone else's account is not affected. */
        assertEquals(200, login("limit2@test.dev", "right-password-1").statusCode());
    }

    @Test
    void aCorrectPasswordStartsTheCountAgain() throws Exception {
        user("limit3@test.dev", "9000000053");

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5; i++) {
                assertEquals(401, login("limit3@test.dev", "wrong").statusCode());
            }

            assertEquals(200, login("limit3@test.dev", "right-password-1").statusCode(), "round " + round);
        }
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndTheSamePause() throws Exception {
        HttpResponse<String> unknown = login("nobody@test.dev", "whatever-1");
        HttpResponse<String> known;

        user("limit4@test.dev", "9000000054");

        known = login("limit4@test.dev", "wrong");

        assertEquals(401, unknown.statusCode());
        assertEquals(401, known.statusCode());
        assertEquals(known.body(), unknown.body(), "the answer does not reveal whether the email exists");

        for (int i = 0; i < 7; i++) {
            login("nobody@test.dev", "again-" + i);
        }

        assertEquals(429, login("nobody@test.dev", "again-last").statusCode());
    }

    @Test
    void anOverlongPasswordIsRefusedCleanlyAtSignInAndNeverCrashes() throws Exception {
        user("limit5@test.dev", "9000000055");

        assertEquals(401, login("limit5@test.dev", "x".repeat(300)).statusCode());
        assertEquals(401, login("limit5@test.dev", "é".repeat(100)).statusCode());
        assertEquals(401, login("x".repeat(400) + "@test.dev", "right-password-1").statusCode());
    }

    @Test
    void manySignUpCodeRequestsFromOneAddressArePaused() throws Exception {
        Http http = new Http(port);

        for (int i = 0; i < 6; i++) {
            assertEquals(200, http.post("/api/auth/send-otp",
                    "{\"email\":\"code" + i + "@test.dev\",\"password\":\"long-enough-1\",\"phone\":\"90000001" + (10 + i) + "\",\"name\":\"Code\"}").statusCode(),
                    "request " + (i + 1));
        }

        HttpResponse<String> paused = http.post("/api/auth/send-otp",
                "{\"email\":\"code9@test.dev\",\"password\":\"long-enough-1\",\"phone\":\"9000000199\",\"name\":\"Code\"}");

        assertEquals(429, paused.statusCode());
        assertTrue(paused.body().contains("Too many code requests"), paused.body());
    }
}
