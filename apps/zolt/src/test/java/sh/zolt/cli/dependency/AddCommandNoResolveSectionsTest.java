package sh.zolt.cli.dependency;

import static sh.zolt.cli.dependency.AddCommandNoResolveTestSupport.writeProjectConfig;
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

final class AddCommandNoResolveSectionsTest {
    @TempDir
    private Path tempDir;

    @Test
    void addAddsApiDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """

                [repositories]
                "central" = "https://repo.maven.apache.org/maven2"

                [dependencies]
                "com.example:contract" = "1.0.0"

                [dependencies.test]
                """);

        CommandResult result = execute(
                "--color=always",
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "com.example:contract:2.0.0",
                "--scope", "api");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "\u001B[32m✔\u001B[0m Updated dependency com.example:contract from 1.0.0 in [dependencies] to 2.0.0 in [dependencies.api]"));
        assertFalse(result.stdout().contains(
                "\u001B[32mUpdated dependency com.example:contract from 1.0.0 in [dependencies] to 2.0.0 in [dependencies.api]\u001B[0m"));
        assertTrue(result.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(result.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.api]\n\"com.example:contract\" = \"2.0.0\""));
        assertFalse(config.contains("[dependencies]\n\"com.example:contract\" = \"1.0.0\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void addAddsManagedApiDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "--managed",
                "com.example:contract",
                "--scope", "api");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "Added dependency com.example:contract with a platform-managed version to [dependencies.api]"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.api]\n\"com.example:contract\" = { managed = true }"));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void addAddsRuntimeDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "--managed",
                "com.h2database:h2",
                "--scope", "runtime");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "Added dependency com.h2database:h2 with a platform-managed version to [dependencies.runtime]"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.runtime]\n\"com.h2database:h2\" = { managed = true }"), config);
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void addAddsProvidedDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "jakarta.servlet:jakarta.servlet-api:6.1.0",
                "--scope", "provided");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "Added dependency jakarta.servlet:jakarta.servlet-api:6.1.0 to [dependencies.provided]"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.provided]\n\"jakarta.servlet:jakarta.servlet-api\" = \"6.1.0\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void addAddsDevDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "--managed",
                "org.springframework.boot:spring-boot-devtools",
                "--scope", "dev");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "Added dependency org.springframework.boot:spring-boot-devtools with a platform-managed version to [dependencies.dev]"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.dev]\n\"org.springframework.boot:spring-boot-devtools\" = { managed = true }"));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }
}
