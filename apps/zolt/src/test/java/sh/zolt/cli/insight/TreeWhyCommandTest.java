package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TreeWhyCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void treePrintsDependencyTreeFromProjectLockfile() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);
        writeAppLibLockfile(projectDir);

        CommandResult result = execute("tree", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                   \\- com.example:lib:1.0.0
                """, result.stdout());
    }

    @Test
    void treePrintsJsonFromProjectLockfile() throws IOException {
        Path projectDir = tempDir.resolve("tree-json");
        writeProjectConfig(projectDir);
        writeExcludedPackageLockfile(projectDir);

        CommandResult result = execute("tree", "--format", "json", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"command\": \"tree\""));
        assertTrue(result.stdout().contains("\"roots\": [\"com.example:app:1.0.0\"]"));
        assertTrue(result.stdout().contains("\"policyEffects\": ["));
        assertTrue(result.stdout().contains("\"id\": \"commons-logging:commons-logging\""));
        assertEquals("", result.stderr());
    }

    @Test
    void treeAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("tree-directory");
        writeProjectConfig(projectDir);
        writeAppLibLockfile(projectDir);

        CommandResult result = execute(
                "tree",
                "--directory", projectDir.toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"command\": \"tree\""));
        assertTrue(result.stdout().contains("\"roots\": [\"com.example:app:1.0.0\"]"));
        assertEquals("", result.stderr());
    }

    @Test
    void treeReportsMissingLockfileCleanly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute("tree", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Could not read zolt.lock"));
    }

    @Test
    void whyPrintsPathFromProjectRootToPackage() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);
        writeAppLibLockfile(projectDir);

        CommandResult result = execute("why", "--cwd", projectDir.toString(), "com.example:lib");

        assertEquals(0, result.exitCode());
        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                   \\- com.example:lib:1.0.0
                """, result.stdout());
    }

    @Test
    void whyPrintsConflictDetailsFromProjectLockfile() throws IOException {
        Path projectDir = tempDir.resolve("why-conflict");
        writeProjectConfig(projectDir);
        writeConflictedLockfile(projectDir);

        CommandResult result = execute("why", "--cwd", projectDir.toString(), "com.example:lib");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "\\- com.example:lib:2.0.0 (conflict: selected 2.0.0; requested 1.0.0, 2.0.0; newest version wins)"));
        assertEquals("", result.stderr());
    }

    @Test
    void whyPrintsJsonForExcludedPackage() throws IOException {
        Path projectDir = tempDir.resolve("why-json");
        writeProjectConfig(projectDir);
        writeExcludedPackageLockfile(projectDir);

        CommandResult result = execute(
                "why",
                "--format", "json",
                "--cwd", projectDir.toString(),
                "commons-logging:commons-logging");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"command\": \"why\""));
        assertTrue(result.stdout().contains("\"target\": \"commons-logging:commons-logging\""));
        assertTrue(result.stdout().contains("\"status\": \"excluded\""));
        assertTrue(result.stdout().contains("\"path\": []"));
        assertTrue(result.stdout().contains(
                "\"policy\": \"[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)\""));
        assertEquals("", result.stderr());
    }

    @Test
    void whyAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("why-directory");
        writeProjectConfig(projectDir);
        writeAppLibLockfile(projectDir);

        CommandResult result = execute(
                "why",
                "--directory", projectDir.toString(),
                "--format", "json",
                "com.example:lib");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"command\": \"why\""));
        assertTrue(result.stdout().contains("\"target\": \"com.example:lib\""));
        assertTrue(result.stdout().contains("\"status\": \"present\""));
        assertEquals("", result.stderr());
    }

    @Test
    void whyReportsMissingPackageClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute("why", "--cwd", projectDir.toString(), "com.example:missing");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Package com.example:missing is not present in zolt.lock"));
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """

                [repositories]
                test = "https://repo.maven.apache.org/maven2"

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                """);
    }

    private static void writeAppLibLockfile(Path projectDir) throws IOException {
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
                dependencies = ["com.example:lib:1.0.0"]

                [[package]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                dependencies = []
                """);
    }

    private static void writeConflictedLockfile(Path projectDir) throws IOException {
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
                dependencies = ["com.example:lib:2.0.0"]

                [[package]]
                id = "com.example:lib"
                version = "2.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                dependencies = []

                [[conflict]]
                id = "com.example:lib"
                selected = "2.0.0"
                requested = ["1.0.0", "2.0.0"]
                reason = "newest version wins"
                """);
    }

    private static void writeExcludedPackageLockfile(Path projectDir) throws IOException {
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

                [[policy]]
                kind = "global-exclusion"
                id = "commons-logging:commons-logging"
                requested = "1.2"
                source = "com.example:app:1.0.0"
                policy = "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)"
                """);
    }
}
