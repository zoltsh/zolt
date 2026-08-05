package sh.zolt.cli.quality;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DoctorCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void doctorReportsEnvironmentHealthOutsideAProject() throws IOException {
        Path emptyDir = tempDir.resolve("not-a-project");
        Files.createDirectories(emptyDir);

        CommandResult result = execute("--color=never", "doctor", "--directory", emptyDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.stdout().contains("Could not read zolt.toml"));
        assertFalse(result.stderr().contains("Could not read zolt.toml"));
        assertFalse(result.stderr().contains("Check that the file exists and is readable."));
        assertTrue(result.stdout().contains("Zolt: ok"));
        assertTrue(result.stdout().contains("version: "));
        assertTrue(result.stdout().contains("JDK: ok"));
        assertTrue(result.stdout().contains("Zolt home: ok"));
    }

    @Test
    void doctorPointsAtInitOutsideAProject() throws IOException {
        Path emptyDir = tempDir.resolve("needs-init");
        Files.createDirectories(emptyDir);

        CommandResult result = execute("--color=never", "doctor", "--directory", emptyDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("skip Not a Zolt project: no zolt.toml in "));
        assertTrue(result.stdout().contains(emptyDir.toAbsolutePath().normalize().toString()));
        assertTrue(result.stdout().contains("Next: zolt init"));
    }

    /**
     * A directory that does not exist is a typo, not an empty machine. Reporting the environment as
     * healthy and offering {@code zolt init} there would greenlight a path Zolt cannot use.
     */
    @Test
    void doctorFailsOnADirectoryThatDoesNotExist() {
        Path missing = tempDir.resolve("definitely/not/here");

        CommandResult result = execute("--color=never", "doctor", "--directory", missing.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("is not an existing directory."), result.stderr());
        assertTrue(result.stderr().contains(missing.toAbsolutePath().normalize().toString()), result.stderr());
        assertFalse(result.stdout().contains("Next: zolt init"), result.stdout());
    }

    /** Inside a project's subdirectory the next step is the project root, never a second `zolt init`. */
    @Test
    void doctorPointsAtTheEnclosingProjectRootFromASubdirectory() throws IOException {
        Path projectDir = tempDir.resolve("enclosing-project");
        writeProjectConfig(projectDir);
        Path subdirectory = projectDir.resolve("src/main/java");
        Files.createDirectories(subdirectory);

        CommandResult result = execute("--color=never", "doctor", "--directory", subdirectory.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.stdout().contains("Next: zolt init"), result.stdout());
        assertTrue(
                result.stdout().contains("project root: " + projectDir.toAbsolutePath().normalize()),
                result.stdout());
        assertTrue(
                result.stdout().contains("Next: zolt doctor --directory "
                        + projectDir.toAbsolutePath().normalize()),
                result.stdout());
    }

    @Test
    void doctorPointsAtTheEnclosingWorkspaceRootFromAMemberDirectory() throws IOException {
        Path workspaceDir = tempDir.resolve("enclosing-workspace");
        Path memberDir = workspaceDir.resolve("modules/member");
        Files.createDirectories(memberDir);
        Files.writeString(workspaceDir.resolve("zolt-workspace.toml"), """
                [workspace]
                members = ["modules/member"]
                """);

        CommandResult result = execute("--color=never", "doctor", "--directory", memberDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.stdout().contains("Next: zolt init"), result.stdout());
        assertTrue(
                result.stdout().contains("workspace root: " + workspaceDir.toAbsolutePath().normalize()),
                result.stdout());
    }

    /** `--self-hosting` reads a project's own layout, so outside one it must say it was skipped. */
    @Test
    void doctorSaysSelfHostingChecksNeedAProject() throws IOException {
        Path emptyDir = tempDir.resolve("self-hosting-outside");
        Files.createDirectories(emptyDir);

        CommandResult result = execute("--color=never", "doctor", "--self-hosting", "--directory", emptyDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("skip Self-hosting checks need a Zolt project"), result.stdout());
        assertFalse(result.stdout().contains("Self-hosting: ok"), result.stdout());
        assertFalse(result.stdout().contains("Self-hosting status:"), result.stdout());
    }

    @Test
    void doctorSkipsProjectChecksOutsideAProject() throws IOException {
        Path emptyDir = tempDir.resolve("no-project-checks");
        Files.createDirectories(emptyDir);

        CommandResult result = execute("--color=never", "doctor", "--directory", emptyDir.toString());
        CommandResult quiet = execute("--quiet", "doctor", "--directory", emptyDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.stdout().contains("Test runtime JDK"));
        assertFalse(result.stdout().contains("Self-hosting"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
    }

    @Test
    void doctorStylesEnvironmentReportWhenColorIsForced() throws IOException {
        Path emptyDir = tempDir.resolve("colored-environment");
        Files.createDirectories(emptyDir);

        CommandResult result = execute("--color=always", "doctor", "--directory", emptyDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Zolt: \u001B[32mok\u001B[0m"));
        assertTrue(result.stdout().contains("JDK: \u001B[32mok\u001B[0m"));
        assertTrue(result.stdout().contains("Zolt home: \u001B[32mok\u001B[0m"));
    }

    @Test
    void doctorStillReportsUnreadableProjectConfig() throws IOException {
        Path projectDir = tempDir.resolve("broken");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), "this is not valid toml\n");

        CommandResult result = execute("--color=never", "doctor", "--directory", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertFalse(result.stdout().contains("Not a Zolt project"));
        assertTrue(result.stderr().contains("error:"));
    }

    /**
     * Doctor must resolve the in-project JDK through the same probe-backed service that
     * {@code zolt toolchain status} and {@code zolt build} use. A shim-installed JDK (jenv, asdf, mise)
     * with no JAVA_HOME resolves only through that probe, so the two commands must never disagree.
     */
    @Test
    void doctorInProjectJdkVerdictMatchesToolchainStatus() throws IOException {
        Path projectDir = tempDir.resolve("toolchain-agreement");
        writeProjectConfig(projectDir);

        CommandResult doctor = execute("--color=never", "doctor", "--directory", projectDir.toString());
        CommandResult toolchainStatus =
                execute("--color=never", "toolchain", "status", "--directory", projectDir.toString());

        assertEquals(
                toolchainStatus.exitCode(),
                doctor.exitCode(),
                "doctor must agree with `toolchain status`; doctor said:\n" + doctor.stdout() + doctor.stderr());
        assertTrue(toolchainStatus.stdout().contains("status: ok"), toolchainStatus.stdout());
        assertTrue(doctor.stdout().contains("JDK: ok"), doctor.stdout());
    }

    /**
     * The pre-probe JDK check only read {@code [project].java} and environment variables, so a
     * {@code [toolchain.java]} table that cannot possibly resolve was reported as healthy.
     */
    @Test
    void doctorHonorsUnsatisfiableToolchainJavaTable() throws IOException {
        Path projectDir = tempDir.resolve("toolchain-table");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [toolchain.java]
                version = "999"
                features = []
                policy = "allow-system"

                [repositories]
                test = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [test.dependencies]

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                """.formatted(currentJavaMajorVersion()));

        CommandResult result = execute("--color=never", "doctor", "--directory", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertFalse(result.stdout().contains("JDK: ok"), result.stdout());
        assertTrue(result.stdout().contains("JDK status: error"), result.stdout());
        assertTrue(result.stderr().contains("Java version mismatch."), result.stderr());
    }

    /**
     * An unusable toolchain still reports the paths it resolved. The version is wrong, not the JDK, so
     * every tool row must name a real path instead of collapsing to `missing`.
     */
    @Test
    void doctorReportsResolvedJdkPathsWhenSomethingIsWrong() throws IOException {
        Path projectDir = tempDir.resolve("resolved-paths");
        writeProjectConfig(projectDir, "999");

        CommandResult result = execute("--color=never", "doctor", "--directory", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("source: "), result.stdout());
        assertFalse(result.stdout().contains("java: missing"), result.stdout());
        assertFalse(result.stdout().contains("javac: missing"), result.stdout());
        assertFalse(result.stdout().contains("jar: missing"), result.stdout());
    }

    @Test
    void doctorReportsJdkStatus() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute("--color=always", "doctor", "--directory", projectDir.toString());
        CommandResult quiet = execute("--quiet", "doctor", "--directory", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("JDK: \u001B[32mok\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[32mJDK\u001B[0m"));
        assertFalse(result.stdout().contains("JDK status:"));
        assertFalse(result.stdout().contains("java: "));
        assertFalse(result.stdout().contains("javac: "));
        assertFalse(result.stdout().contains("jar: "));
        assertFalse(result.stdout().contains("version: "));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
    }

    @Test
    void doctorShowsJdkDetailRowsWhenSomethingIsWrong() throws IOException {
        Path projectDir = tempDir.resolve("version-detail");
        writeProjectConfig(projectDir, "999");

        CommandResult result = execute("--color=always", "doctor", "--directory", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("JDK status: \u001B[31merror\u001B[0m"));
        assertFalse(result.stdout().contains("JDK: \u001B[32mok\u001B[0m"));
        assertTrue(result.stdout().contains("JAVA_HOME: "));
        assertTrue(result.stdout().contains("java: "));
        assertTrue(result.stdout().contains("javac: "));
        assertTrue(result.stdout().contains("jar: "));
        assertTrue(result.stdout().contains("version: " + currentJavaMajorVersion()));
    }

    @Test
    void doctorStylesJdkProblemErrorPrefixWhenColorIsForced() throws IOException {
        Path projectDir = tempDir.resolve("version-mismatch");
        writeProjectConfig(projectDir, "999");

        CommandResult result = execute("--color=always", "doctor", "--directory", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("\u001B[31merror:\u001B[0m Java version mismatch."));
        assertTrue(result.stderr().contains("\u001B[31merror:\u001B[0m Project health check failed."));
        assertFalse(result.stderr().contains("\u001B[31merror: Java version mismatch."));
        assertFalse(result.stderr().contains("\u001B[31merror: Project health check failed."));
    }

    @Test
    void doctorReportsSelfHostingReadiness() throws IOException {
        Path projectDir = tempDir.resolve("self-hosting-ready");
        writeSelfHostingProjectConfig(projectDir, true);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.createDirectories(projectDir.resolve("src/test/java"));

        CommandResult result = execute("--color=always", "doctor", "--self-hosting", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("JDK: \u001B[32mok\u001B[0m"));
        assertTrue(result.stdout().contains("Self-hosting: \u001B[32mok\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[32mSelf-hosting\u001B[0m"));
        assertFalse(result.stdout().contains("Self-hosting status:"));
        assertFalse(result.stdout().contains("main class - project main is com.example.Main"));
        assertFalse(result.stdout().contains("JUnit Platform Console"));
        assertFalse(result.stdout().contains("native no-fallback"));
    }

    @Test
    void doctorReportsSelfHostingGapsWithNextSteps() throws IOException {
        Path projectDir = tempDir.resolve("self-hosting-gaps");
        writeSelfHostingProjectConfig(projectDir, false);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.createDirectories(projectDir.resolve("src/test/java"));

        CommandResult result = execute("--color=always", "doctor", "--self-hosting", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("JDK: \u001B[32mok\u001B[0m"));
        assertTrue(result.stdout().contains("Self-hosting status: \u001B[31merror\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[31mSelf-hosting\u001B[0m status"));
        assertTrue(result.stdout().contains("\u001B[31merror:\u001B[0m JUnit Platform Console - add org.junit.platform:junit-platform-console-standalone to [test.dependencies]"));
        assertFalse(result.stdout().contains("\u001B[31merror: JUnit Platform Console"));
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        writeProjectConfig(projectDir, currentJavaMajorVersion());
    }

    private static void writeProjectConfig(Path projectDir, String javaVersion) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [repositories]
                test = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [test.dependencies]

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                """.formatted(javaVersion));
    }

    private static void writeSelfHostingProjectConfig(Path projectDir, boolean includeTestRunner) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                %s
                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"

                [native]
                imageName = "demo"
                output = "target/native"
                args = ["--no-fallback"]
                """.formatted(
                currentJavaMajorVersion(),
                includeTestRunner
                        ? """
                        [test.dependencies]
                        "org.junit.platform:junit-platform-console-standalone" = "1.11.4"

                        """
                        : ""));
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
