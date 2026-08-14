package sh.zolt.cli.testcmd;

import sh.zolt.cli.CliTestSupport;


import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestCommandTimingsTest extends TestCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void testCommandPrintsNestedJsonTimingsWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), CliTestSupport.memberConfig("demo"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeDemoTestSource(projectDir);

        CommandResult result = execute(
                "test",
                "--tests", "*DemoTest",
                "--include-tag", "fast",
                "--exclude-tag", "slow",
                "--timings",
                "--timings-format", "json",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("Tests passed"));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(6, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"config read\""));
        assertTrue(lines[0].contains("\"depth\":0"));
        assertTrue(lines[1].contains("\"phase\":\"build test inputs\""));
        assertTrue(lines[1].contains("\"depth\":2"));
        assertTrue(lines[1].contains("\"mainCompilationSkipped\""));
        assertTrue(lines[1].contains("\"mainCompilationMode\""));
        assertTrue(lines[1].contains("\"mainIncrementalFallbackReason\""));
        assertTrue(lines[1].contains("\"mainSourcesRecompiled\""));
        assertTrue(lines[1].contains("\"mainDependentSourcesRecompiled\""));
        assertTrue(lines[1].contains("\"mainAbiChangedClasses\""));
        assertTrue(lines[1].contains("\"mainFingerprintCheckNanos\""));
        assertTrue(lines[1].contains("\"mainFingerprintWriteNanos\""));
        assertTrue(lines[2].contains("\"phase\":\"compile test sources\""));
        assertTrue(lines[2].contains("\"depth\":2"));
        assertTrue(lines[2].contains("\"testSourceFiles\":\"1\""));
        assertTrue(lines[2].contains("\"testCompilationSkipped\":\"false\""));
        assertTrue(lines[2].contains("\"testCompilationMode\""));
        assertTrue(lines[2].contains("\"testIncrementalFallbackReason\""));
        assertTrue(lines[2].contains("\"testSourcesRecompiled\""));
        assertTrue(lines[2].contains("\"testDependentSourcesRecompiled\""));
        assertTrue(lines[2].contains("\"testAbiChangedClasses\""));
        assertTrue(lines[2].contains("\"testFingerprintCheckNanos\""));
        assertTrue(lines[2].contains("\"testFingerprintWriteNanos\""));
        assertTrue(lines[3].contains("\"phase\":\"compile tests\""));
        assertTrue(lines[3].contains("\"depth\":1"));
        assertTrue(lines[3].contains("\"testSourceFiles\":\"1\""));
        assertTrue(lines[3].contains("\"testCompilationSkipped\":\"false\""));
        assertTrue(lines[3].contains("\"testCompilationMode\""));
        assertTrue(lines[3].contains("\"testIncrementalFallbackReason\""));
        assertTrue(lines[3].contains("\"testSourcesRecompiled\""));
        assertTrue(lines[3].contains("\"testDependentSourcesRecompiled\""));
        assertTrue(lines[3].contains("\"testAbiChangedClasses\""));
        assertTrue(lines[3].contains("\"testFingerprintCheckNanos\""));
        assertTrue(lines[3].contains("\"testFingerprintWriteNanos\""));
        assertTrue(lines[4].contains("\"phase\":\"execute tests\""));
        assertTrue(lines[4].contains("\"depth\":1"));
        assertTrue(lines[4].contains("\"testRunner\":\"junit-console\""));
        assertTrue(lines[4].contains("\"testRuntimeClasspathEntries\""));
        assertTrue(lines[4].contains("\"testLauncherClasspathEntries\""));
        assertTrue(lines[4].contains("\"testDiscoveryScanRoots\""));
        assertTrue(lines[4].contains("\"testPatterns\":\"1\""));
        assertTrue(lines[4].contains("\"testIncludedTags\":\"1\""));
        assertTrue(lines[4].contains("\"testExcludedTags\":\"1\""));
        assertTrue(lines[4].contains("\"testRunnerRequestNanos\""));
        assertTrue(lines[4].contains("\"testSlowEntries\":\"0\""));
        assertTrue(lines[4].contains("\"testSlowEvidence\":\"unavailable\""));
        assertTrue(lines[4].contains("\"outputBytes\""));
        assertTrue(lines[5].contains("\"phase\":\"run tests\""));
        assertTrue(lines[5].contains("\"depth\":0"));
        assertTrue(lines[5].contains("\"testRunner\":\"junit-console\""));
        assertTrue(lines[5].contains("\"testSourceFiles\":\"1\""));
        assertTrue(lines[5].contains("\"testRuntimeClasspathEntries\""));
        assertTrue(lines[5].contains("\"testLauncherClasspathEntries\""));
        assertTrue(lines[5].contains("\"testDiscoveryScanRoots\""));
        assertTrue(lines[5].contains("\"testPatterns\":\"1\""));
        assertTrue(lines[5].contains("\"testIncludedTags\":\"1\""));
        assertTrue(lines[5].contains("\"testExcludedTags\":\"1\""));
        assertTrue(lines[5].contains("\"testRunnerRequestNanos\""));
        assertTrue(lines[5].contains("\"testSlowEntries\":\"0\""));
        assertTrue(lines[5].contains("\"testSlowEvidence\":\"unavailable\""));
    }

    @Test
    void compileOnlyCompilesTestsWithoutStartingTheRunner() throws IOException {
        Path projectDir = tempDir.resolve("compile-only");
        Path cacheRoot = tempDir.resolve("compile-only-cache");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), CliTestSupport.memberConfig("demo"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        writeDemoTestSource(projectDir);

        CommandResult result = execute(
                "test",
                "--compile-only",
                "--timings",
                "--timings-format", "json",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Tests compiled"));
        assertFalse(result.stdout().contains("fake console"));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/DemoTest.class")));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(4, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"config read\""));
        assertTrue(lines[1].contains("\"phase\":\"build test inputs\""));
        assertTrue(lines[1].contains("\"depth\":1"));
        assertTrue(lines[2].contains("\"phase\":\"compile test sources\""));
        assertTrue(lines[2].contains("\"depth\":1"));
        assertTrue(lines[3].contains("\"phase\":\"compile tests\""));
        assertTrue(lines[3].contains("\"depth\":0"));
    }
}
