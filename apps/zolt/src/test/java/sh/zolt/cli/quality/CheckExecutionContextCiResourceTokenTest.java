package sh.zolt.cli.quality;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckExecutionContextCiResourceTokenTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiRejectsMissingResourceTokenEnvironment() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-missing-resource-token-env");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-missing-resource-token-env") + """

                [resources.filtering]
                enabled = true
                includes = ["**/*.properties"]

                [resources.tokens]
                buildNumber = { env = "ZOLT_TEST_MISSING_RESOURCE_TOKEN" }
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context [resources.tokens.buildNumber] CI context requires environment variable ZOLT_TEST_MISSING_RESOURCE_TOKEN"));
        assertTrue(result.stdout().contains("resource token `buildNumber` before resource copying"));
        assertTrue(result.stdout().contains("Values are never printed"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiReportsResourceTokenProvenance() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-resource-token-provenance");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-resource-token-provenance") + """

                [resources.filtering]
                enabled = true
                includes = ["**/*.properties"]

                [resources.tokens]
                appName = { value = "demo" }
                projectVersion = { project = "version" }
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context resource-token-inputs CI resource token preflight passed for 2 tokens: env=0, project=1, literal=1."));
        assertFalse(result.stdout().contains("demo"));
        assertEquals("", result.stderr());
    }
}
