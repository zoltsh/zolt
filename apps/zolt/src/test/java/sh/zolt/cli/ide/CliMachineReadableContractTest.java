package sh.zolt.cli.ide;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.ContentAddressedLockTestSupport.cachedJar;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;
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

final class CliMachineReadableContractTest {
    @TempDir
    private Path tempDir;

    @Test
    void planJsonKeepsStableCompatibilityEnvelope() throws IOException {
        Path projectDir = tempDir.resolve("plan-contract");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-contract"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "plan",
                "--format", "json",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
        assertTrue(result.stdout().startsWith("{"));
        assertTrue(result.stdout().contains("\"schemaVersion\": 1")
                || result.stdout().contains("\"schemaVersion\":1"));
        assertTrue(result.stdout().contains("\"projectRoot\": \"" + projectDir.toAbsolutePath().normalize()));
        assertTrue(result.stdout().contains("\"project\": \"plan-contract\""));
        assertTrue(result.stdout().contains("\"target\": \"package\""));
        assertTrue(result.stdout().contains("\"status\": \"ready\""));
        assertTrue(result.stdout().contains("\"nodes\": ["));
        assertTrue(result.stdout().contains("\"id\": \"lockfile\""));
        assertTrue(result.stdout().contains("\"blockers\": []"));
        assertFalse(result.stdout().contains("Zolt plan"));
    }

    @Test
    void testPlanJsonKeepsStableCompatibilityEnvelope() throws IOException {
        Path projectDir = tempDir.resolve("test-plan-contract");
        Files.createDirectories(projectDir.resolve("target/test-classes/com/example"));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("test-plan-contract"));
        Files.write(projectDir.resolve("target/test-classes/com/example/DemoTest.class"), new byte[] {0});

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "test",
                "plan",
                "--format", "json",
                "--shard-count", "2",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"projectRoot\": \"" + projectDir.toAbsolutePath().normalize()));
        assertTrue(result.stdout().contains("\"project\": \"test-plan-contract\""));
        assertTrue(result.stdout().contains("\"suite\": {\n    \"name\": \"all\""));
        assertTrue(result.stdout().contains("\"entries\": [\"com.example.DemoTest\"]"));
        assertTrue(result.stdout().contains("\"shards\": ["));
        assertTrue(result.stdout().contains("\"empty\": true"));
        assertTrue(result.stdout().contains("\"arguments\": [\"test\", \"--directory\""));
        assertFalse(result.stdout().contains("Test plan for test-plan-contract"));
    }

    @Test
    void treeJsonKeepsStableCompatibilityEnvelope() throws IOException {
        Path projectDir = tempDir.resolve("tree-contract");
        writeProjectConfig(projectDir);
        writeLockfile(projectDir);

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "tree",
                "--format", "json",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"command\": \"tree\""));
        assertTrue(result.stdout().contains("\"coordinate\": \"com.example:demo:0.1.0\""));
        assertTrue(result.stdout().contains("\"packages\": ["));
        assertTrue(result.stdout().contains("\"coordinate\": \"com.example:app:1.0.0\""));
        assertTrue(result.stdout().contains("\"roots\": [\"com.example:app:1.0.0\"]"));
        assertTrue(result.stdout().contains("\"policyEffects\": []"));
        assertFalse(result.stdout().contains("\\- com.example:app"));
    }

    @Test
    void whyJsonKeepsStableCompatibilityEnvelope() throws IOException {
        Path projectDir = tempDir.resolve("why-contract");
        writeProjectConfig(projectDir);
        writeLockfile(projectDir);

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "why",
                "--format", "json",
                "--cwd", projectDir.toString(),
                "com.example:app");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"command\": \"why\""));
        assertTrue(result.stdout().contains("\"target\": \"com.example:app\""));
        assertTrue(result.stdout().contains("\"status\": \"present\""));
        assertTrue(result.stdout().contains("\"coordinate\": \"com.example:app:1.0.0\""));
        assertTrue(result.stdout().contains("\"conflicts\": []"));
        assertTrue(result.stdout().contains("\"policyEffects\": []"));
        assertFalse(result.stdout().contains("\\- com.example:app"));
    }

    @Test
    void representativeSadPathKeepsExitCodeLocationAndRemediation() throws IOException {
        Path projectDir = tempDir.resolve("missing-config");
        Files.createDirectories(projectDir);

        CommandResult result = execute("--color=never", "build", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Could not read zolt.toml at "
                + projectDir.resolve("zolt.toml")));
        assertTrue(result.stderr().contains("File: " + projectDir.resolve("zolt.toml")));
        assertTrue(result.stderr().contains("Next: Check that the file exists and is readable."));
        assertNoAnsi(result.stderr());
        assertEquals("", result.stdout());
    }

    @Test
    void representativeSadPathForcedColorStylesOnlyErrorPrefix() throws IOException {
        Path projectDir = tempDir.resolve("missing-config-forced-color");
        Files.createDirectories(projectDir);

        CommandResult result = execute("--color=always", "build", "--cwd", projectDir.toString());

        String errorPrefix = "\u001B[31merror:\u001B[0m";
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().startsWith(errorPrefix + " Could not read zolt.toml at "
                + projectDir.resolve("zolt.toml") + "."));
        assertTrue(result.stderr().contains("File: " + projectDir.resolve("zolt.toml")));
        assertTrue(result.stderr().contains("Next: Check that the file exists and is readable."));
        assertFalse(result.stderr().replace(errorPrefix, "error:").contains("\u001B["));
        assertEquals("", result.stdout());
    }

    @Test
    void classpathOutputIgnoresForcedColorAndProgress() throws IOException {
        Path projectDir = tempDir.resolve("classpath-contract");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(projectDir);
        write(projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:app"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:app"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/app/1.0.0/app-1.0.0.jar"
                dependencies = []
                """);

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "compile");

        assertEquals(0, result.exitCode());
        assertEquals(
                cachedJar(projectDir.resolve("zolt.lock"), cacheRoot, "com.example:app")
                        + System.lineSeparator(),
                result.stdout());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
    }

    @Test
    void checkJsonIgnoresForcedColorAndProgress() throws IOException {
        Path projectDir = tempDir.resolve("check-json-contract");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-json-contract"));

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "check",
                "--format", "json",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
        assertTrue(result.stdout().startsWith(
                "{\"schemaVersion\":1,\"command\":\"check\",\"status\":\"ok\",\"projectRoot\":\""));
        assertTrue(result.stdout().contains("\"diagnostics\":[]"));
        assertTrue(result.stdout().contains("\"id\":\"command-surface\""));
        assertFalse(result.stdout().contains("Checking project"));
        assertFalse(result.stdout().contains("Checked 1 quality checks"));
    }

    @Test
    void stableJsonCommandsKeepAJsonFailureEnvelope() throws IOException {
        Path projectDir = tempDir.resolve("stable-json-failures");
        Files.createDirectories(projectDir);

        assertJsonFailure(
                "check",
                execute("check", "--format", "json", "--cwd", projectDir.toString()));
        assertJsonFailure(
                "outdated",
                execute("outdated", "--format", "json", "--cwd", projectDir.toString()));
        assertJsonFailure(
                "update",
                execute("update", "--format", "json", "--cwd", projectDir.toString()));
        assertJsonFailure(
                "toolchain status",
                execute("toolchain", "status", "--format", "json", "--cwd", projectDir.toString()));
    }

    private static void assertJsonFailure(String command, CommandResult result) {
        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().startsWith("{"));
        assertJsonField(result.stdout(), "schemaVersion", "1");
        assertJsonField(result.stdout(), "command", "\"" + command + "\"");
        assertJsonField(result.stdout(), "status", "\"failed\"");
        assertTrue(result.stdout().contains("\"diagnostics\":[")
                || result.stdout().contains("\"diagnostics\": ["));
        assertJsonField(result.stdout(), "severity", "\"error\"");
    }

    private static void assertJsonField(String json, String name, String renderedValue) {
        assertTrue(json.contains("\"" + name + "\":" + renderedValue)
                || json.contains("\"" + name + "\": " + renderedValue));
    }

    @Test
    void explainJsonIgnoresForcedColorAndProgress() throws IOException {
        Path projectDir = tempDir.resolve("explain-json-contract");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "explain",
                "--cwd", projectDir.toString(),
                "--source", "maven",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"source\": \"maven\""));
        assertTrue(result.stdout().contains("\"root\": \"" + projectDir.toAbsolutePath().normalize()));
        assertTrue(result.stdout().contains("\"name\": \"demo\""));
        assertFalse(result.stdout().contains("Zolt explain: Maven project"));
    }

    @Test
    void ideModelJsonIgnoresForcedColorAndProgress() throws IOException {
        Path projectDir = tempDir.resolve("ide-model-json-contract");
        Path cacheRoot = tempDir.resolve("ide-model-cache");
        IdeModelCommandTestSupport.writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "ide",
                "model",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertNoAnsi(result.stdout());
        assertNoProgressText(result.stdout());
        assertTrue(result.stdout().contains("\"schemaVersion\": 1"));
        assertTrue(result.stdout().contains("\"project\": {\n    \"name\": \"demo\""));
        assertTrue(result.stdout().contains("\"paths\": {\n    \"root\": \""
                + projectDir.toAbsolutePath().normalize()));
        assertFalse(result.stdout().contains("Exported IDE model"));
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """

                [repositories]
                central = false

                [repositories.test]
                url = "https://repo.maven.apache.org/maven2"
                """);
    }

    private static void writeLockfile(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:app"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:app"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """);
    }

    private static void assertNoAnsi(String output) {
        assertFalse(output.contains("\u001B["), "output should not contain ANSI: " + output);
    }

    private static void assertNoProgressText(String output) {
        assertFalse(output.contains("Resolving "), "output should not contain progress text: " + output);
        assertFalse(output.contains("Building "), "output should not contain progress text: " + output);
        assertFalse(output.contains("Packaging "), "output should not contain progress text: " + output);
        assertFalse(output.contains("Explaining "), "output should not contain progress text: " + output);
        assertFalse(output.contains("Exporting "), "output should not contain progress text: " + output);
        assertFalse(output.contains("Still running:"), "output should not contain progress text: " + output);
    }
}
