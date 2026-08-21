package sh.zolt.cli.update;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.ZoltCli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolchainNoticeTest {
    @TempDir
    private Path tempDir;

    @Test
    void successfulCommandPrintsToolchainInstallNoticeWhenForced() throws IOException {
        Path project = toolchainProject("0.2.0");
        Path installRoot = tempDir.resolve("home/.zolt");

        CommandResult result = execute(
                "--toolchain-check", "always",
                "--toolchain-check-cwd", project.toString(),
                "--toolchain-check-install-root", installRoot.toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(ZoltCli.version()));
        assertTrue(result.stderr().contains("This project wants Zolt 0.2.0"));
        assertTrue(result.stderr().contains("this command is running " + ZoltCli.version()));
        assertTrue(result.stderr().contains("Run `zolt self install 0.2.0`"));
    }

    @Test
    void successfulCommandPrintsToolchainUseNoticeWhenVersionIsInstalled() throws IOException {
        Path project = toolchainProject("0.2.0");
        Path installRoot = tempDir.resolve("home/.zolt");
        writeFakeZolt(installRoot.resolve("versions/0.2.0/bin/zolt"), "0.2.0");

        CommandResult result = execute(
                "--toolchain-check", "always",
                "--toolchain-check-cwd", project.toString(),
                "--toolchain-check-install-root", installRoot.toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(ZoltCli.version()));
        assertTrue(result.stderr().contains("This project wants Zolt 0.2.0"));
        assertTrue(result.stderr().contains("Run `zolt self use 0.2.0`"));
    }

    @Test
    void matchingToolchainVersionDoesNotPrintNotice() throws IOException {
        Path project = toolchainProject(ZoltCli.version());

        CommandResult result = execute(
                "--toolchain-check", "always",
                "--toolchain-check-cwd", project.toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(ZoltCli.version()));
        assertEquals("", result.stderr());
    }

    private Path toolchainProject(String zoltVersion) throws IOException {
        Path project = tempDir.resolve("toolchain-project-" + zoltVersion.replace('.', '-'));
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "toolchain-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.zolt]
                version = "%s"
                """.formatted(zoltVersion));
        return project;
    }

    private static void writeFakeZolt(Path executable, String version) throws IOException {
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--version" ]]; then
                  printf '%s\\n' "%s"
                  exit 0
                fi
                exit 0
                """.formatted("%s", version));
        executable.toFile().setExecutable(true);
    }
}
