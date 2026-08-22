package sh.zolt.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

final class PackageModeResolutionBoundaryCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void packageRejectsResolutionChangingModeBeforeWritingState() throws IOException {
        assertResolutionChangingOverrideRejected("package", "--mode", "spring-boot");
    }

    @Test
    void packagePlanRejectsResolutionChangingModeBeforeWritingState() throws IOException {
        assertResolutionChangingOverrideRejected("package", "--plan", "--mode", "spring-boot");
    }

    @Test
    void runPackageRejectsResolutionChangingModeBeforeWritingState() throws IOException {
        assertResolutionChangingOverrideRejected("run-package", "--mode", "spring-boot");
    }

    @Test
    void packagePlanUsesPackagingLocalModeWithoutChangingTheLock() throws IOException {
        Path project = tempDir.resolve("plan-packaging-local");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), memberConfig("demo"));
        Path cacheRoot = tempDir.resolve("plan-cache");
        assertEquals(0, execute(
                "resolve",
                "--cwd", project.toString(),
                "--cache-root", cacheRoot.toString()).exitCode());
        String lockfile = Files.readString(project.resolve("zolt.lock"));

        CommandResult result = execute(
                "package",
                "--plan",
                "--mode", "uber-jar",
                "--cwd", project.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Mode: uber"), result.stdout());
        assertEquals(lockfile, Files.readString(project.resolve("zolt.lock")));
    }

    @Test
    void runPackageUsesPackagingLocalModeWithoutChangingTheLock() throws IOException {
        Path project = tempDir.resolve("run-packaging-local");
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(project.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"
                """);
        Files.writeString(project.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("packaging-local");
                    }
                }
                """);
        Path cacheRoot = tempDir.resolve("run-cache");
        assertEquals(0, execute(
                "resolve",
                "--cwd", project.toString(),
                "--cache-root", cacheRoot.toString()).exitCode());
        String lockfile = Files.readString(project.resolve("zolt.lock"));

        CommandResult result = execute(
                "run-package",
                "--mode", "uber-jar",
                "--cwd", project.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("packaging-local"), result.stdout());
        assertEquals(lockfile, Files.readString(project.resolve("zolt.lock")));
    }

    private void assertResolutionChangingOverrideRejected(String... command) throws IOException {
        Path project = tempDir.resolve(command[0] + "-" + command.length);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), memberConfig("demo"));
        String[] args = new String[command.length + 4];
        System.arraycopy(command, 0, args, 0, command.length);
        args[command.length] = "--cwd";
        args[command.length + 1] = project.toString();
        args[command.length + 2] = "--cache-root";
        args[command.length + 3] = tempDir.resolve("cache").toString();

        CommandResult result = execute(args);

        assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stderr().contains("changes dependency-resolution tooling"), result.stderr());
        assertTrue(result.stderr().contains("Set `[package].mode = \"spring-boot\"`"), result.stderr());
        assertTrue(result.stderr().contains("run `zolt resolve`"), result.stderr());
        assertFalse(Files.exists(project.resolve("zolt.lock")));
        assertFalse(Files.exists(project.resolve("target")));
    }
}
