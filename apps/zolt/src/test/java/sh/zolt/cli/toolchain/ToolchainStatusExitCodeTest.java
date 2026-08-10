package sh.zolt.cli.toolchain;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolchainStatusExitCodeTest {
    @TempDir
    private Path tempDir;

    @Test
    void unhealthyStatusReturnsFailureInBothHumanAndJsonFormats() throws IOException {
        Path project = tempDir.resolve("unhealthy-status-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"
                """);
        String[] common = {
                "toolchain",
                "status",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("missing-toolchains").toString()
        };

        var human = execute(common);
        var json = execute(
                "toolchain",
                "status",
                "--json",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("missing-toolchains").toString());

        assertEquals(1, human.exitCode());
        assertTrue(human.stderr().contains("Java toolchain lock metadata is missing for linux-x64"));
        assertEquals(1, json.exitCode());
        assertEquals("", json.stderr());
        assertTrue(json.stdout().contains("\"ok\": false"));
        assertTrue(json.stdout().contains("\"command\": \"toolchain status\""));
        assertTrue(json.stdout().contains("\"status\": \"failed\""));
        assertTrue(json.stdout().contains("\"diagnostics\": ["));
        assertTrue(json.stdout().contains("Java toolchain lock metadata is missing for linux-x64"));

        var formatJson = execute(
                "toolchain",
                "status",
                "--format",
                "json",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("missing-toolchains").toString());
        assertEquals(json.exitCode(), formatJson.exitCode());
        assertEquals(json.stdout(), formatJson.stdout());
        assertEquals(json.stderr(), formatJson.stderr());
    }

    @Test
    void unhealthyGlobalJsonStatusReturnsFailure() throws IOException {
        Path configPath = tempDir.resolve("home/unhealthy-config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
                version = 1

                [defaults.toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"
                """);

        var result = execute(
                "toolchain",
                "global",
                "status",
                "--json",
                "--config",
                configPath.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("missing-toolchains").toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"ok\": false"));
        assertTrue(result.stdout().contains("\"command\": \"toolchain global status\""));
        assertTrue(result.stdout().contains("\"status\": \"failed\""));
        assertTrue(result.stdout().contains("Java toolchain lock metadata is missing for linux-x64"));

        var formatResult = execute(
                "toolchain",
                "global",
                "status",
                "--format",
                "json",
                "--config",
                configPath.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("missing-toolchains").toString());
        assertEquals(result.exitCode(), formatResult.exitCode());
        assertEquals(result.stdout(), formatResult.stdout());
        assertEquals(result.stderr(), formatResult.stderr());
    }

    @Test
    void legacyGlobalStatusUsesOneJsonCommandIdOnSuccessAndFailure() {
        Path missingConfig = tempDir.resolve("home/missing-config.toml");

        var result = execute(
                "toolchain",
                "status",
                "--global",
                "--format",
                "json",
                "--config",
                missingConfig.toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"command\": \"toolchain global status\""), result.stdout());
    }

    @Test
    void contradictoryJsonAndTextFormatIsRejectedActionably() {
        var result = execute(
                "toolchain",
                "status",
                "--json",
                "--format",
                "text",
                "--global",
                "--config",
                tempDir.resolve("home/config.toml").toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"command\": \"toolchain global status\""), result.stdout());
        assertTrue(result.stdout().contains("--json and --format cannot be used together"), result.stdout());
    }
}
