package sh.zolt.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JdkDetectorTest {
    @TempDir
    private Path tempDir;

    @Test
    void detectsToolsFromJavaHome() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        JdkDetector detector = detector(
                Map.of("JAVA_HOME", javaHome.toString(), "PATH", ""),
                java -> Optional.of("openjdk version \"21.0.2\" 2024-01-16"));

        JdkStatus status = detector.detect("21");

        assertTrue(status.ok());
        assertEquals(javaHome, status.javaHome().orElseThrow());
        assertEquals("21", status.version().orElseThrow());
    }

    @Test
    void readsJavaVersionFromJdkReleaseFileWithoutStartingJava() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        Files.writeString(javaHome.resolve("release"), """
                IMPLEMENTOR="Example"
                JAVA_VERSION="21.0.8"
                """);
        int[] processReads = new int[1];
        JdkDetector detector = detector(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> {
                    processReads[0]++;
                    return Optional.of("openjdk version \"17.0.10\"");
                });

        JdkStatus status = detector.detect("21");

        assertTrue(status.ok());
        assertEquals("21", status.version().orElseThrow());
        assertEquals(0, processReads[0]);
    }

    @Test
    void fallsBackToJavaProcessWhenReleaseFileHasNoVersion() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        Files.writeString(javaHome.resolve("release"), "IMPLEMENTOR=\"Example\"\n");
        int[] processReads = new int[1];
        JdkDetector detector = detector(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> {
                    processReads[0]++;
                    return Optional.of("openjdk version \"21.0.8\"");
                });

        JdkStatus status = detector.detect("21");

        assertTrue(status.ok());
        assertEquals(1, processReads[0]);
    }

    @Test
    void detectsToolsFromPathWhenJavaHomeIsUnset() throws IOException {
        Path bin = tempDir.resolve("bin");
        tool(bin, "java");
        tool(bin, "javac");
        tool(bin, "jar");
        JdkDetector detector = detector(
                Map.of("PATH", bin.toString()),
                java -> Optional.of("java version \"17.0.10\""));

        JdkStatus status = detector.detect("17");

        assertTrue(status.ok());
        assertTrue(status.javaHome().isEmpty());
        assertEquals(bin.resolve("java"), status.java().orElseThrow());
    }

    @Test
    void detectsWindowsExecutableNames() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java.exe");
        tool(javaHome, "javac.exe");
        tool(javaHome, "jar.exe");
        Function<String, String> env = Map.of("JAVA_HOME", javaHome.toString())::get;
        JdkDetector detector = new JdkDetector(
                env,
                java.io.File.pathSeparator,
                "Windows 11",
                Optional.empty(),
                java -> Optional.of("openjdk version \"21.0.2\""));

        JdkStatus status = detector.detect("21");

        assertTrue(status.ok());
        assertEquals(javaHome.resolve("bin/java.exe"), status.java().orElseThrow());
    }

    @Test
    void missingToolsProduceActionableProblems() {
        JdkDetector detector = detector(Map.of("PATH", ""), java -> Optional.empty());

        JdkStatus status = detector.detect("21");

        assertFalse(status.ok());
        assertTrue(status.problems().contains("Missing `java`. Install a JDK and set JAVA_HOME or add java to PATH."));
        assertTrue(status.problems().contains("Missing `javac`. Install a JDK and set JAVA_HOME or add javac to PATH."));
        assertTrue(status.problems().contains("Missing `jar`. Install a JDK and set JAVA_HOME or add jar to PATH."));
    }

    @Test
    void versionMismatchProducesActionableProblem() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        JdkDetector detector = detector(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> Optional.of("openjdk version \"17.0.10\""));

        JdkStatus status = detector.detect("21");

        assertFalse(status.ok());
        assertTrue(status.problems().contains(
                "Java version mismatch. zolt.toml requires 21 or newer but detected 17. Install Java 21 or newer, set JAVA_HOME to a suitable JDK, or update [project].java."));
    }

    @Test
    void newerJdkSatisfiesOlderProjectJavaBaseline() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        JdkDetector detector = detector(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> Optional.of("openjdk version \"21.0.2\""));

        JdkStatus status = detector.detect("8");

        assertTrue(status.ok());
        assertTrue(status.versionSatisfies());
        assertTrue(status.problems().isEmpty());
    }

    @Test
    void legacyRequiredJavaFeatureNotationMatchesNewerJdk() {
        JdkStatus status = status("21", "1.8");

        assertTrue(status.ok());
        assertTrue(status.versionSatisfies());
        assertTrue(status.problems().isEmpty());
    }

    @Test
    void legacyAndModernJavaEightNotationAreEquivalent() {
        assertTrue(status("1.8", "8").versionSatisfies());
        assertTrue(status("8", "1.8").versionSatisfies());
    }

    @Test
    void unparseableJavaVersionsKeepExactMatchFallback() {
        assertTrue(status("latest", "latest").versionSatisfies());
        assertFalse(status("21", "latest").versionSatisfies());
        assertTrue(status("1.x", "1.x").versionSatisfies());
        assertFalse(status("21", "1.x").versionSatisfies());
    }

    @Test
    void reusesDetectedToolchainForRepeatedChecks() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        int[] versionReads = new int[1];
        JdkDetector detector = detector(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> {
                    versionReads[0]++;
                    return Optional.of("openjdk version \"21.0.2\"");
                });

        JdkStatus first = detector.detect("21");
        JdkStatus second = detector.detect("17");

        assertTrue(first.ok());
        assertTrue(second.ok());
        assertEquals(1, versionReads[0]);
        assertEquals("21", second.version().orElseThrow());
    }

    @Test
    void parsesLegacyJavaEightVersion() {
        assertEquals("8", JdkDetector.majorVersion("java version \"1.8.0_402\"").orElseThrow());
    }

    @Test
    void missingRuntimeJavaHomeIsIgnored() {
        assertTrue(JdkDetector.runtimeJavaHome(null).isEmpty());
        assertTrue(JdkDetector.runtimeJavaHome("").isEmpty());
        assertTrue(JdkDetector.runtimeJavaHome("   ").isEmpty());
    }

    private static JdkDetector detector(
            Map<String, String> environment,
            JdkDetector.ToolVersionReader versionReader) {
        Function<String, String> env = environment::get;
        return new JdkDetector(env, java.io.File.pathSeparator, "Linux", Optional.empty(), versionReader);
    }

    private static JdkStatus status(String detected, String required) {
        return new JdkStatus(
                Optional.of(Path.of("/jdk")),
                Optional.of(Path.of("/jdk/bin/java")),
                Optional.of(Path.of("/jdk/bin/javac")),
                Optional.of(Path.of("/jdk/bin/jar")),
                Optional.of(detected),
                required);
    }

    private static Path tool(Path javaHomeOrBin, String name) throws IOException {
        Path bin = javaHomeOrBin.getFileName().toString().equals("bin")
                ? javaHomeOrBin
                : javaHomeOrBin.resolve("bin");
        Files.createDirectories(bin);
        Path tool = bin.resolve(name);
        Files.writeString(tool, "");
        tool.toFile().setExecutable(true);
        return tool;
    }
}
