package com.monetra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creating an account: ask for a code, check it, then sign in. With email not set up and dev mode on,
 * the code is written to the server log instead of being emailed, and these tests read it from there.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-registration",
                "spring.mail.host=localhost",
                "spring.mail.username=",
                "monetra.log-otp=true",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class RegistrationFlowTest {

    @LocalServerPort
    int port;

    private static String body(String email, String password, String phone) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"phone\":\"" + phone + "\",\"name\":\"New Person\"}";
    }

    /** The code the server wrote to its log for this email. */
    private static String codeFor(CapturedOutput output, String email) {
        Matcher m = Pattern.compile("The OTP for " + Pattern.quote(email) + " is (\\d{6})").matcher(output.getAll());

        String last = null;

        while (m.find()) {
            last = m.group(1);
        }

        assertTrue(last != null, "the server logged a code for " + email);

        return last;
    }

    @Test
    void anAccountIsCreatedOnlyWithTheRightCodeAndCanThenSignIn(CapturedOutput output) throws Exception {
        Http http = new Http(port);

        assertEquals(200, http.post("/api/auth/send-otp", body("reg1@test.dev", "long-enough-1", "9000000041")).statusCode());

        String code = codeFor(output, "reg1@test.dev");
        String wrong = code.equals("000000") ? "111111" : "000000";

        /* A wrong code creates nothing; signing in is still refused. */
        HttpResponse<String> bad = http.post("/api/auth/verify-otp", "{\"email\":\"reg1@test.dev\",\"otp\":\"" + wrong + "\"}");

        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("Incorrect OTP"), bad.body());
        assertEquals(401, new Http(port).post("/api/auth/login", "{\"email\":\"reg1@test.dev\",\"password\":\"long-enough-1\"}").statusCode());

        /* The right code creates the account. */
        assertEquals(200, http.post("/api/auth/verify-otp", "{\"email\":\"reg1@test.dev\",\"otp\":\"" + code + "\"}").statusCode());

        Http signedIn = Http.login(port, "reg1@test.dev", "long-enough-1");

        assertTrue(signedIn.get("/api/auth/me").body().contains("reg1@test.dev"));

        /* The same code cannot be used twice, and the email cannot be registered again. */
        assertEquals(400, http.post("/api/auth/verify-otp", "{\"email\":\"reg1@test.dev\",\"otp\":\"" + code + "\"}").statusCode());
        assertEquals(400, http.post("/api/auth/send-otp", body("reg1@test.dev", "long-enough-1", "9000000041")).statusCode());
    }

    @Test
    void aCodeCannotBeGuessedByTryingManyTimes(CapturedOutput output) throws Exception {
        Http http = new Http(port);

        assertEquals(200, http.post("/api/auth/send-otp", body("reg2@test.dev", "long-enough-2", "9000000042")).statusCode());

        String code = codeFor(output, "reg2@test.dev");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertEquals(400, http.post("/api/auth/verify-otp", "{\"email\":\"reg2@test.dev\",\"otp\":\"" + wrong + "\"}").statusCode());
        }

        /* After five wrong tries even the right code is refused: a new one has to be requested. */
        HttpResponse<String> late = http.post("/api/auth/verify-otp", "{\"email\":\"reg2@test.dev\",\"otp\":\"" + code + "\"}");

        assertEquals(400, late.statusCode());
        assertTrue(late.body().contains("Too many attempts") || late.body().contains("Request a new OTP"), late.body());
        assertEquals(401, new Http(port).post("/api/auth/login", "{\"email\":\"reg2@test.dev\",\"password\":\"long-enough-2\"}").statusCode());
    }

    @Test
    void badSignUpDetailsAreRefusedWithAClearMessage() throws Exception {
        Http http = new Http(port);

        assertEquals(400, http.post("/api/auth/send-otp", body("not-an-email", "long-enough-3", "9000000043")).statusCode());
        assertEquals(400, http.post("/api/auth/send-otp", body("reg3@test.dev", "short", "9000000043")).statusCode());
        assertEquals(400, http.post("/api/auth/send-otp", body("reg3@test.dev", "long-enough-3", "123")).statusCode());

        /* BCrypt reads only 72 bytes, so a longer password is refused instead of being quietly cut short. */
        assertEquals(400, http.post("/api/auth/send-otp", body("reg3@test.dev", "x".repeat(100), "9000000043")).statusCode());
    }
}
