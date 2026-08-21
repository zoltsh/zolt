package sh.zolt.cli.testplan;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.testcmd.TestCommandTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestPlanCommandTest extends TestCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void testPlanReportsSuiteMembershipAndFilters() throws IOException {
        Path projectDir = tempDir.resolve("plan-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        appendSuite(projectDir, """

                [test.suites.fast]
                classes = ["*Test"]
                excludeClasses = ["*ContractTest"]
                tags = ["fast"]
                excludeTags = ["slow"]
                """);
        writeClass(projectDir, "target/test-classes/com/example/FastServiceTest.class");
        writeClass(projectDir, "target/test-classes/com/example/UserContractTest.class");

        CommandResult result = execute(
                "test",
                "plan",
                "--cwd", projectDir.toString(),
                "--suite", "fast");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Test plan for demo"));
        assertTrue(result.stdout().contains("suite: fast"));
        assertTrue(result.stdout().contains("configured suite: yes"));
        assertTrue(result.stdout().contains("matched entries: 1"));
        assertTrue(result.stdout().contains("empty: no"));
        assertTrue(result.stdout().contains("class filters: include *Test; exclude *ContractTest"));
        assertTrue(result.stdout().contains("tag filters: include fast; exclude slow"));
        assertTrue(result.stdout().contains("entries: 1"));
        assertTrue(result.stdout().contains("- com.example.FastServiceTest"));
        assertFalse(Files.exists(projectDir.resolve("target/test-reports")));
        assertFalse(Files.exists(projectDir.resolve("target/coverage")));
    }

    @Test
    void testPlanShowsEmptySuiteClearly() throws IOException {
        Path projectDir = tempDir.resolve("empty-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        appendSuite(projectDir, """

                [test.suites.fast]
                classes = ["*MissingTest"]
                """);
        writeClass(projectDir, "target/test-classes/com/example/FastServiceTest.class");

        CommandResult result = execute(
                "test",
                "plan",
                "--cwd", projectDir.toString(),
                "--suite", "fast");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("suite: fast"));
        assertTrue(result.stdout().contains("matched entries: 0"));
        assertTrue(result.stdout().contains("empty: yes"));
    }

    @Test
    void testPlanReportsOverlappingSuiteMembership() throws IOException {
        Path projectDir = tempDir.resolve("overlap-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        appendSuite(projectDir, """

                [test.suites.fast]
                classes = ["*Test"]

                [test.suites.contract]
                classes = ["*ContractTest"]
                """);
        writeClass(projectDir, "target/test-classes/com/example/UserContractTest.class");

        CommandResult result = execute(
                "test",
                "plan",
                "--cwd", projectDir.toString(),
                "--suite", "fast");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("matched entries: 1"));
        assertTrue(result.stdout().contains("overlapping entries: 1"));
        assertTrue(result.stdout().contains("- com.example.UserContractTest also matches contract"));
    }

    @Test
    void testPlanRejectsUnknownSuite() throws IOException {
        Path projectDir = tempDir.resolve("unknown-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "test",
                "plan",
                "--cwd", projectDir.toString(),
                "--suite", "missing");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown test suite `missing`"));
        assertTrue(result.stderr().contains("Add [test.suites.missing] to zolt.toml"));
    }

    @Test
    void testPlanShowsShardPlanWithoutWritingManifests() throws IOException {
        Path projectDir = tempDir.resolve("shard-plan-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        appendSuite(projectDir, """

                [test.suites.fast]
                classes = ["*Test"]
                """);
        writeClass(projectDir, "target/test-classes/com/example/AlphaTest.class");
        writeClass(projectDir, "target/test-classes/com/example/BetaTest.class");
        writeClass(projectDir, "target/test-classes/com/example/GammaTest.class");

        CommandResult result = execute(
                "test",
                "plan",
                "--cwd", projectDir.toString(),
                "--suite", "fast",
                "--shard-count", "2");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("shards: 2"));
        assertTrue(result.stdout().contains("- shard 1/2: 2 entries, empty: no, manifest: target/test-shards/fast/shard-1-of-2.json"));
        assertTrue(result.stdout().contains("- shard 2/2: 1 entries, empty: no, manifest: target/test-shards/fast/shard-2-of-2.json"));
        assertFalse(Files.exists(projectDir.resolve("target/test-shards")));
    }

    @Test
    void testPlanBalancesShardsFromProfileHistory() throws IOException {
        Path projectDir = tempDir.resolve("balanced-shard-plan-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        appendSuite(projectDir, """

                [test.suites.fast]
                classes = ["*Test"]
                """);
        writeClass(projectDir, "target/test-classes/com/example/AlphaTest.class");
        writeClass(projectDir, "target/test-classes/com/example/BetaTest.class");
        writeClass(projectDir, "target/test-classes/com/example/GammaTest.class");
        writeProfile(projectDir.resolve("target/profile.json"));

        CommandResult result = execute(
                "test",
                "plan",
                "--cwd", projectDir.toString(),
                "--suite", "fast",
                "--shard-count", "2",
                "--balance-from", "target/profile.json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("balancing: profile-history"));
        assertTrue(result.stdout().contains("balance profile: " + projectDir.resolve("target/profile.json").toAbsolutePath().normalize()));
        assertTrue(result.stdout().contains("missing profile history: 1"));
        assertTrue(result.stdout().contains("- com.example.GammaTest"));
        assertTrue(result.stdout().contains("unmatched profile history: 1"));
        assertTrue(result.stdout().contains("- com.example.UnusedTest"));
        assertTrue(result.stdout().contains("- shard 1/2: 2 entries, empty: no, estimated: 120 ms"));
        assertTrue(result.stdout().contains("- shard 2/2: 1 entries, empty: no, estimated: 80 ms"));
    }

    @Test
    void testPlanBalanceFromRequiresShardCount() throws IOException {
        Path projectDir = tempDir.resolve("balance-without-shards");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "test",
                "plan",
                "--cwd", projectDir.toString(),
                "--balance-from", "target/profile.json");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("--balance-from requires --shard-count"));
    }

    @Test
    void testPlanJsonReportsWorkspaceShardMatrixCommands() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-shard-plan-json");
        Path memberDir = workspaceDir.resolve("modules/api");
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace-shard-plan-json"

                [workspace.members]
                include = ["modules/api"]

                [repositories.test]
                url = "https://repo.maven.apache.org/maven2"
                """);
        writeProjectConfig(memberDir, "https://repo.maven.apache.org/maven2");
        // A workspace member cannot own dependency repositories; the root universe is authoritative.
        Files.writeString(memberDir.resolve("zolt.toml"),
                Files.readString(memberDir.resolve("zolt.toml")).replace("""
                        [repositories.test]
                        url = "https://repo.maven.apache.org/maven2"

                        """.replace("                        ", "                "), ""));
        appendSuite(memberDir, """

                [test.suites.fast]
                classes = ["*Test"]
                """);
        writeClass(memberDir, "target/test-classes/com/example/AlphaTest.class");
        writeClass(memberDir, "target/test-classes/com/example/BetaTest.class");

        CommandResult result = execute(
                "test",
                "plan",
                "--directory", memberDir.toString(),
                "--suite", "fast",
                "--shard-count", "2",
                "--reports-dir", "target/test-reports",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"project\": \"demo\""));
        assertTrue(result.stdout().contains("\"member\": \"modules/api\""));
        assertTrue(result.stdout().contains("\"name\": \"fast\""));
        assertTrue(result.stdout().contains("\"entryCount\": 2"));
        assertTrue(result.stdout().contains("\"label\": \"1/2\""));
        assertTrue(result.stdout().contains("\"manifest\": \"target/test-shards/fast/shard-1-of-2.json\""));
        assertTrue(result.stdout().contains("""
                      "arguments": ["test", "--workspace", "--member", "modules/api", "--suite", "fast", "--shard", "1/2", "--reports-dir", "target/test-reports"]
                """));
        assertFalse(result.stdout().contains("Test plan for demo"));
        assertFalse(Files.exists(memberDir.resolve("target/test-shards")));
    }

    private static void appendSuite(Path projectDir, String config) throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), config, StandardOpenOption.APPEND);
    }

    private static void writeClass(Path projectDir, String relativePath) throws IOException {
        Path classFile = projectDir.resolve(relativePath);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[] {0});
    }

    private static void writeProfile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {
                  "schemaVersion": 1,
                  "tests": [],
                  "containers": [
                    {
                      "className": "com.example.AlphaTest",
                      "durationMillis": 120
                    },
                    {
                      "className": "com.example.BetaTest",
                      "durationMillis": 80
                    },
                    {
                      "className": "com.example.UnusedTest",
                      "durationMillis": 900
                    }
                  ]
                }
                """);
    }
}
