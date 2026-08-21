package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspacePackageModeOverrideRejectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void packageRejectsWorkspaceModeOverrideBeforeResolutionOrBuild()
            throws IOException {
        Path workspace = workspace("package-mode-override");

        CommandResult result = execute(
                "package",
                "--workspace",
                "--all",
                "--mode",
                "spring-boot",
                "--cwd",
                workspace.toString());

        assertRejected(result, "zolt package");
        assertFalse(Files.exists(workspace.resolve("zolt.lock")));
        assertFalse(Files.exists(
                workspace.resolve("apps/app/target")));
    }

    @Test
    void runPackageRejectsWorkspaceModeOverrideBeforeResolutionOrBuild()
            throws IOException {
        Path workspace = workspace("run-package-mode-override");

        CommandResult result = execute(
                "run-package",
                "--workspace",
                "--all",
                "--mode",
                "spring-boot",
                "--cwd",
                workspace.toString());

        assertRejected(result, "zolt run-package");
        assertFalse(Files.exists(workspace.resolve("zolt.lock")));
        assertFalse(Files.exists(
                workspace.resolve("apps/app/target")));
    }

    private Path workspace(String name) throws IOException {
        Path workspace = tempDir.resolve(name);
        Path member = workspace.resolve("apps/app");
        Files.createDirectories(member);
        Files.writeString(
                workspace.resolve("zolt-workspace.toml"),
                """
                [workspace]
                name = "mode-override"

                [workspace.members]
                include = ["apps/app"]
                """);
        Files.writeString(
                member.resolve("zolt.toml"),
                """
                [project]
                name = "app"
                version = "0.1.0"
                group = "com.example"
                java = %s
                """.formatted(currentJavaMajorVersion()));
        Path source = member.resolve(
                "src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                """
                package com.example;
                public final class Main {
                }
                """);
        return workspace;
    }

    private static void assertRejected(
            CommandResult result,
            String command) {
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "`"
                        + command
                        + " --workspace --mode` is not supported"));
        assertTrue(result.stderr().contains(
                "Set [package].mode on the selected workspace members"));
        assertTrue(result.stderr().contains(
                "zolt resolve --workspace"));
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }
}
