package sh.zolt.cli.quality;

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

final class CheckProjectModelCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkWorkspaceProjectModelReportsUnusedVersionAliasesByMember() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-unused-version-alias");
        Path apiDir = workspaceDir.resolve("api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-unused-version-alias"

                [workspace.members]
                include = ["api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """

                [versions]
                boot = "4.0.6"
                lombok = "1.18.36"
                openapi = "7.11.0"
                test-lombok = "1.18.36"
                tomcat = "10.1.40"
                used = "1.0.0"
                unused = "2.0.0"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = { versionRef = "boot" }

                [dependencies]
                "org.example:lib" = { versionRef = "used" }

                [dependencies.processor]
                "org.projectlombok:lombok" = { versionRef = "lombok" }

                [dependencies.test-processor]
                "org.projectlombok:lombok" = { versionRef = "test-lombok" }

                [dependencies.constraints]
                "org.apache.tomcat.embed:tomcat-embed-core" = { versionRef = "tomcat" }

                [generated.tools.openapi]
                kind = "openapi"
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "openapi"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """);

        CommandResult result = execute(
                "check",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--check", "project-model");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("skip project-model api [versions].unused Version alias `unused` is declared but not referenced by any versionRef."));
        assertFalse(result.stdout().contains("[versions].used"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkProjectModelReportsInvalidProjectPaths() throws IOException {
        Path projectDir = tempDir.resolve("check-invalid-model");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-invalid-model") + """

                [build]
                sources = ["/tmp/source"]
                """);

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "project-model");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error project-model [build].source Path `/tmp/source` must be project-relative"));
        assertTrue(result.stdout().contains("next: Edit zolt.toml to use a relative path"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkProjectModelJsonReportsCompilerReleaseFailures() throws IOException {
        Path projectDir = tempDir.resolve("check-release-model");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-release-model") + """

                [compiler]
                release = "99"
                """);

        CommandResult result = execute(
                "check",
                "--format", "json",
                "--cwd", projectDir.toString(),
                "--check", "project-model");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("\"status\":\"failed\""));
        assertTrue(result.stdout().contains("\"id\":\"project-model\""));
        assertTrue(result.stdout().contains("\"subject\":\"[compiler].release\""));
        assertTrue(result.stdout().contains("Compiler release `99` is newer than [project].java"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkProjectModelJsonReportsUnusedVersionAliases() throws IOException {
        Path projectDir = tempDir.resolve("check-unused-version-alias");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-unused-version-alias") + """

                [versions]
                boot = "4.0.6"
                lombok = "1.18.36"
                test-lombok = "1.18.36"
                tomcat = "10.1.40"
                used = "1.0.0"
                unused = "2.0.0"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = { versionRef = "boot" }

                [dependencies]
                "org.example:lib" = { versionRef = "used" }

                [dependencies.processor]
                "org.projectlombok:lombok" = { versionRef = "lombok" }

                [dependencies.test-processor]
                "org.projectlombok:lombok" = { versionRef = "test-lombok" }

                [dependencies.constraints]
                "org.apache.tomcat.embed:tomcat-embed-core" = { versionRef = "tomcat" }
                """);

        CommandResult result = execute(
                "check",
                "--format", "json",
                "--cwd", projectDir.toString(),
                "--check", "project-model");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"status\":\"ok\""));
        assertTrue(result.stdout().contains("\"subject\":\"[versions].unused\""));
        assertTrue(result.stdout().contains("Version alias `unused` is declared but not referenced by any versionRef."));
        assertTrue(result.stdout().contains("\"status\":\"skipped\""));
        assertFalse(result.stdout().contains("\"subject\":\"[versions].boot\""));
        assertFalse(result.stdout().contains("\"subject\":\"[versions].lombok\""));
        assertFalse(result.stdout().contains("\"subject\":\"[versions].openapi\""));
        assertFalse(result.stdout().contains("\"subject\":\"[versions].test-lombok\""));
        assertFalse(result.stdout().contains("\"subject\":\"[versions].tomcat\""));
        assertFalse(result.stdout().contains("\"subject\":\"[versions].used\""));
        assertEquals("", result.stderr());
    }
}
