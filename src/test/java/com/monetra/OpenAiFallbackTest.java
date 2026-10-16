package com.monetra;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** When the online AI cannot be reached, the AI on this computer answers instead. Uses a fake local AI. */
class OpenAiFallbackTest {

    /** Nothing listens here, so connecting fails at once, like a dropped connection to the online AI. */
    private static final String UNREACHABLE = "http://127.0.0.1:1/v1";

    private HttpServer localAi;
    private final AtomicInteger asked = new AtomicInteger();

    @BeforeEach
    void startLocalAi() throws Exception {
        localAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        localAi.createContext("/v1/chat/completions", exchange -> {
            asked.incrementAndGet();

            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"Answer from the laptop AI.\"}}]}".getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        localAi.start();
    }

    @AfterEach
    void stopLocalAi() {
        localAi.stop(0);
    }

    private OpenAiCompatibleClient client(String fallbackUrl) {
        OpenAiCompatibleClient c = new OpenAiCompatibleClient();

        ReflectionTestUtils.setField(c, "baseUrl", UNREACHABLE);
        ReflectionTestUtils.setField(c, "apiKey", "test-key");
        ReflectionTestUtils.setField(c, "model", "online-model");
        ReflectionTestUtils.setField(c, "fallbackUrl", fallbackUrl);
        ReflectionTestUtils.setField(c, "fallbackModel", "llama3.2");

        return c;
    }

    @Test
    void theLaptopAiAnswersWhenTheOnlineOneCannotBeReached() {
        String local = "http://127.0.0.1:" + localAi.getAddress().getPort() + "/v1";

        assertEquals("Answer from the laptop AI.", client(local).ask("You are a test.", "Hello", 50));
        assertEquals(1, asked.get());
    }

    @Test
    void withoutABackupTheErrorIsPassedOn() {
        assertThrows(ResourceAccessException.class, () -> client("").ask("You are a test.", "Hello", 50));
        assertEquals(0, asked.get());
    }
}
