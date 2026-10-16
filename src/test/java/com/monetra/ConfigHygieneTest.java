package com.monetra;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys live only in the environment (read from a private .env by start-local.ps1), never in a file that is uploaded.
 * These tests fail the day someone pastes a real key into a tracked file, or loosens the .gitignore rules.
 */
class ConfigHygieneTest {

    /** A secret setting must look like ${NAME:}: it reads the environment and defaults to empty. */
    private static final Pattern EMPTY_PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z][A-Z0-9_]*:}$");

    /** Setting names that can hold a secret. */
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)secret|password|api-key|client-id|token|instance-id|webhook-id");

    /** The only secret-looking settings that may hold something else, each for a stated reason. */
    private static final Map<String, String> ALLOWED = Map.of(
            "spring.datasource.password", "empty: the local H2 file database has no password",
            "setu.aa.token-url", "a public web address, not a secret",
            "monetra.demo-password", "the public password of the two local demo accounts, documented in the README; "
                    + "set MONETRA_DEMO_PASSWORD before going online");

    /** A long mix of letters and digits (a key, a secret or an id) that is not a plain word or a line of dashes. */
    private static final Pattern KEY_SHAPED = Pattern.compile("(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]{32,}");

    private static String read(String file) throws Exception {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8);
    }

    private static Properties applicationProperties() throws Exception {
        Properties p = new Properties();

        try (InputStream in = ConfigHygieneTest.class.getResourceAsStream("/application.properties")) {
            p.load(in);
        }

        return p;
    }

    @Test
    void everySecretSettingReadsTheEnvironmentAndDefaultsToEmpty() throws Exception {
        Properties p = applicationProperties();

        StringBuilder wrong = new StringBuilder();
        int checked = 0;

        for (String name : p.stringPropertyNames()) {
            if (!SECRET_NAME.matcher(name).find()) {
                continue;
            }

            checked++;

            if (!ALLOWED.containsKey(name) && !EMPTY_PLACEHOLDER.matcher(p.getProperty(name)).matches()) {
                wrong.append("\n  ").append(name).append(" is not an empty ${NAME:} placeholder");
            }
        }

        assertEquals("", wrong.toString(), "settings that could hold a secret");
        assertTrue(checked >= 8, "the secret settings were found (checked " + checked + ")");
    }

    @Test
    void theIntegrationsStillReadTheNamesTheStartScriptSets() throws Exception {
        Properties p = applicationProperties();

        assertEquals("${PAYPAL_CLIENT_ID:}", p.getProperty("paypal.client-id"));
        assertEquals("${PAYPAL_CLIENT_SECRET:}", p.getProperty("paypal.client-secret"));
        assertEquals("${PAYPAL_WEBHOOK_ID:}", p.getProperty("paypal.webhook-id"));
        assertEquals("${SETU_AA_CLIENT_ID:}", p.getProperty("setu.aa.client-id"));
        assertEquals("${SETU_AA_CLIENT_SECRET:}", p.getProperty("setu.aa.client-secret"));
        assertEquals("${SETU_AA_PRODUCT_INSTANCE_ID:}", p.getProperty("setu.aa.product-instance-id"));
        assertEquals("${AI_API_KEY:}", p.getProperty("ai.api-key"));
        assertEquals("${ANTHROPIC_API_KEY:}", p.getProperty("anthropic.api-key"));
    }

    @Test
    void theGitignoreKeepsPrivateFilesOutAndTheExampleIn() throws Exception {
        List<String> rules = read(".gitignore").lines().map(line -> line.trim()).toList();

        for (String rule : List.of(".env", ".env.*", "!.env.example", "start-local.ps1", "data/", "*.mv.db")) {
            assertTrue(rules.contains(rule), ".gitignore has the rule " + rule);
        }

        /* A negation only works when it comes after the rule it undoes. */
        assertTrue(rules.indexOf("!.env.example") > rules.indexOf(".env.*"), "!.env.example comes after .env.*");

        List<String> docker = read(".dockerignore").lines().map(line -> line.trim()).toList();

        assertTrue(docker.contains(".env") && docker.contains(".env.*"), ".dockerignore keeps .env out of the image build");
    }

    @Test
    void theExampleFileListsEveryKeyWithNoValue() throws Exception {
        String example = read(".env.example");

        StringBuilder filled = new StringBuilder();
        int names = 0;

        for (String line : example.lines().toList()) {
            Matcher m = Pattern.compile("^([A-Z][A-Z0-9_]*)=(.*)$").matcher(line);

            if (m.matches()) {
                names++;

                if (!m.group(2).isBlank()) {
                    filled.append("\n  ").append(m.group(1));
                }
            }
        }

        assertEquals("", filled.toString(), "example entries that hold a value");

        for (String name : List.of("PAYPAL_CLIENT_ID", "PAYPAL_CLIENT_SECRET", "PAYPAL_WEBHOOK_ID", "SETU_AA_CLIENT_ID",
                "SETU_AA_CLIENT_SECRET", "SETU_AA_PRODUCT_INSTANCE_ID", "GROQ_API_KEY", "ANTHROPIC_API_KEY")) {
            assertTrue(example.contains("\n" + name + "="), ".env.example lists " + name);
        }

        assertTrue(names >= 8, "names listed: " + names);
    }

    @Test
    void theDetectorsCatchAKeyAndLeaveOrdinaryTextAlone() {
        /* Made-up text of the same shape as a key. */
        assertTrue(KEY_SHAPED.matcher("AbCdEf0123456789AbCdEf0123456789AbCd").find());
        assertTrue(KEY_SHAPED.matcher("12345678-abcd-4ef0-9a1b-123456789abc").find());

        assertTrue(!KEY_SHAPED.matcher("# " + "-".repeat(60)).find());
        assertTrue(!KEY_SHAPED.matcher("https://api-m.sandbox.paypal.com").find());
        assertTrue(!KEY_SHAPED.matcher("# --- PayPal Sandbox: developer.paypal.com ---").find());

        assertTrue(EMPTY_PLACEHOLDER.matcher("${PAYPAL_CLIENT_ID:}").matches());
        assertTrue(!EMPTY_PLACEHOLDER.matcher("${PAYPAL_CLIENT_ID:abc}").matches());
        assertTrue(!EMPTY_PLACEHOLDER.matcher("abc").matches());
    }

    @Test
    void noKeyShapedTextIsWrittenInTheConfigurationFiles() throws Exception {
        StringBuilder found = new StringBuilder();

        for (String file : List.of("src/main/resources/application.properties", "render.yaml", ".env.example", "Dockerfile",
                "start-local.ps1")) {
            if (!Files.exists(Path.of(file))) {
                continue;
            }

            Matcher m = KEY_SHAPED.matcher(read(file));

            while (m.find()) {
                found.append("\n  ").append(file).append(": a ").append(m.group().length()).append("-character key-like string");
            }
        }

        assertEquals("", found.toString(), "key-shaped text in configuration files");
    }
}
