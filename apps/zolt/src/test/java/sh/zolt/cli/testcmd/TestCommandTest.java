package sh.zolt.cli.testcmd;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestCommandTest extends TestCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void testCommandWritesJUnitReportsWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("reports-demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("reports-demo"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeDemoTestSource(projectDir);
        CommandResult result = execute(
                "--progress=always",
                "test",
                "--reports-dir", "target/test-reports",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        Path report = projectDir.resolve("target/test-reports/TEST-fake-console.xml");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("✔ Tests passed"));
        assertTrue(result.stdout().contains("→ wrote "
                + projectDir.resolve("target/test-reports").toAbsolutePath().normalize()));
        assertTrue(result.stderr().contains("Testing project..."));
        assertTrue(result.stderr().contains("Tested project"));
        assertTrue(Files.exists(report));
        assertTrue(Files.readString(report).contains("testsuite"));
    }

    @Test
    void testCommandPrintsRequestedEventOutput() throws IOException {
        Path projectDir = tempDir.resolve("events-demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("events-demo"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeDemoTestSource(projectDir);
        CommandResult result = execute(
                "test",
                "--test-event", "failed",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("fake console event output"));
        assertTrue(result.stdout().contains("Tests passed"));
    }

    @Test
    void testColorsOnlyHumanSummaryLeadFragmentsWhenForced() throws IOException {
        Path projectDir = tempDir.resolve("color-demo");
        Path cacheRoot = tempDir.resolve("cache-color");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("color-demo"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeDemoTestSource(projectDir);
        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "test",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("\u001B[36mTesting\u001B[0m color-demo"));
        assertFalse(result.stdout().contains("\u001B[36mTesting color-demo"));
        assertTrue(result.stdout().contains("1 test source files\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[32m✔\u001B[0m Tests passed"));
        assertFalse(result.stdout().contains("\u001B[32mTests passed\u001B[0m"));
        assertTrue(result.stderr().contains("\u001B[36mTesting\u001B[0m project..."));
        assertTrue(result.stderr().contains("\u001B[32mTested\u001B[0m project"));
        assertFalse(result.stderr().contains("\u001B[36mTesting project...")
                || result.stderr().contains("\u001B[32mTested project"));
    }

    @Test
    void testAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("directory-demo");
        Path cacheRoot = tempDir.resolve("cache-directory");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("directory-demo"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeDemoTestSource(projectDir);
        CommandResult result = execute(
                "test",
                "--directory", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("Tests passed"));
    }

    @Test
    void testReportsMissingJUnitConsoleClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");
        Path testSource = projectDir.resolve("src/test/java/com/example/MainTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, "package com.example; public final class MainTest {}\n");

        CommandResult result = execute(
                "test",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("JUnit Platform Console is not present"));
    }

    @Test
    void testSuiteRejectsUnknownSuiteClearly() throws IOException {
        Path projectDir = tempDir.resolve("unknown-suite-demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");
        writeDemoTestSource(projectDir);

        CommandResult result = execute(
                "test",
                "--suite", "missing",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown test suite `missing`"), result.stderr());
        assertTrue(result.stderr().contains("Add [test.suites.missing] to zolt.toml"));
    }

    @Test
    void testShardWritesManifestAndRunsSelectedShard() throws IOException {
        Path projectDir = tempDir.resolve("shard-demo");
        Path cacheRoot = tempDir.resolve("cache-shard");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.toml"), """

                [test.suites.fast]
                includeClassname = ["*Test"]
                """, StandardOpenOption.APPEND);
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");
        writeTestSource(projectDir, "AlphaTest");
        writeTestSource(projectDir, "BetaTest");
        writeTestSource(projectDir, "GammaTest");

        CommandResult result = execute(
                "test",
                "--suite", "fast",
                "--shard", "2/2",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Tests passed"));
        String manifest = Files.readString(projectDir.resolve("target/test-shards/fast/shard-2-of-2.json"));
        assertTrue(manifest.contains("\"suite\": \"fast\""));
        assertTrue(manifest.contains("\"index\": 2"));
        assertTrue(manifest.contains("\"total\": 2"));
        assertTrue(manifest.contains("\"selectedEntries\": 1"));
        assertTrue(manifest.contains("\"com.example.BetaTest\""));
        assertFalse(manifest.contains("\"com.example.AlphaTest\""));
        assertFalse(manifest.contains("\"com.example.GammaTest\""));
    }

    private static void writeTestSource(Path projectDir, String name) throws IOException {
        Path testSource = projectDir.resolve("src/test/java/com/example/" + name + ".java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, "package com.example; public final class " + name + " {}\n");
    }
}
