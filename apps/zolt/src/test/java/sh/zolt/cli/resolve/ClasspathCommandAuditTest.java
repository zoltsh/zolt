package sh.zolt.cli.resolve;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClasspathCommandAuditTest {
    @TempDir
    private Path tempDir;

    @Test
    void classpathAuditPrintsLanePolicyAndResolvedPackages() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeAuditLockfile(projectDir);

        CommandResult result = execute("classpath", "--cwd", projectDir.toString(), "audit");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Classpath lane audit"));
        assertTrue(result.stdout().contains(
                "provided            yes     no      no   no        no             no              no           no            no            no              provided-container"));
        assertTrue(result.stdout().contains(
                "- com.example:devtools:1.0.0 [dev] lanes=runtime,test package=development-only"));
        assertTrue(result.stdout().contains(
                "- jakarta.servlet:jakarta.servlet-api:6.1.0 [provided] lanes=compile package=provided-container"));
        assertEquals("", result.stderr());
    }

    @Test
    void classpathAuditPrintsJsonForTooling() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeAuditLockfile(projectDir);

        CommandResult result = execute("classpath", "--cwd", projectDir.toString(), "audit", "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"command\": \"classpath audit\""));
        assertTrue(result.stdout().contains("\"scope\": \"provided\""));
        assertTrue(result.stdout().contains("\"lanes\": [\"compile\"]"));
        assertTrue(result.stdout().contains("\"disposition\": \"provided-container\""));
        assertEquals("", result.stderr());
    }

    @Test
    void classpathJsonFormatIsOnlyForAudit() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute("classpath", "--cwd", projectDir.toString(), "compile", "--format", "json");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Use `zolt classpath audit --format json`."));
    }

    private static void writeAuditLockfile(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                lane = "provided"
                resolvedScope = "provided"

                [[dependencyRoot]]
                member = "."
                id = "com.example:devtools"
                version = "1.0.0"
                lane = "dev"
                resolvedScope = "dev"

                [[package]]
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                source = "maven-central"
                scope = "provided"
                direct = true
                jar = "jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:devtools"
                version = "1.0.0"
                source = "maven-central"
                scope = "dev"
                direct = true
                jar = "com/example/devtools/1.0.0/devtools-1.0.0.jar"
                dependencies = []
                """);
    }
}
