package sh.zolt.cli.packaging;

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

final class PackagePlanJsonTest extends PackagePlanCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void packagePlanJsonUsesStableShapeForThinJar() throws IOException {
        Path projectDir = tempDir.resolve("package-plan-json");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("package-plan-json"));
        writePackagePlanLockfile(projectDir, true, false);

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "package",
                "--plan",
                "--format", "json",
                "--cwd", projectDir.toString(),
                "--cache-root", projectDir.resolve(".zolt/cache").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("\u001B["), "JSON output should not contain ANSI: " + result.stdout());
        assertFalse(result.stdout().contains("Packaging "), "JSON output should not contain progress text: " + result.stdout());
        assertTrue(result.stdout().startsWith("{\n"));
        assertTrue(result.stdout().contains("\"mode\": \"jar\""));
        assertTrue(result.stdout().contains("\"runtimeClasspath\": \"" + projectDir.resolve("target/package-plan-json-0.1.0.runtime-classpath")));
        assertTrue(result.stdout().contains("\"coordinate\": \"com.example:runtime-lib:1.0.0\""));
        assertTrue(result.stdout().contains("\"lanes\": [\"runtime\", \"test\"]"));
        assertTrue(result.stdout().contains("\"packageDefault\": true"));
        assertTrue(result.stdout().contains("\"laneDisposition\": \"package-default\""));
        assertTrue(result.stdout().contains("\"coordinate\": \"com.example:devtools:1.0.0\""));
        assertTrue(result.stdout().contains("\"packageDefault\": false"));
        assertTrue(result.stdout().contains("\"laneDisposition\": \"development-only\""));
        assertTrue(result.stdout().contains("\"disposition\": \"runtime-classpath\""));
        assertTrue(result.stdout().contains("\"rule\": \"thin-runtime-classpath\""));
        assertTrue(result.stdout().contains("\"policies\": [\"strict-version: com.example:runtime-lib -> 1.0.0 (security baseline)\"]"));
        assertTrue(result.stdout().contains("\"warnings\": []"));
    }
}
