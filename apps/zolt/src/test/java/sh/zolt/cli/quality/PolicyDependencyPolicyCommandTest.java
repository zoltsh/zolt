package sh.zolt.cli.quality;

import sh.zolt.cli.CliTestSupport;


import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PolicyDependencyPolicyCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkDependencyPolicyPassesWithoutConfiguredPolicy() throws IOException {
        Path projectDir = tempDir.resolve("check-dependency-policy-empty");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), CliTestSupport.memberConfig("check-dependency-policy-empty"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");

        CommandResult result = execute(
                "check",
                "--check", "dependency-policy",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "ok dependency-policy check-dependency-policy-empty Dependency policy baseline is explainable: 0 platforms, 0 constraints, 0 exclusions, and 0 direct explicit versions."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkDependencyPolicyReportsDirectExclusionConflicts() throws IOException {
        Path projectDir = tempDir.resolve("check-dependency-policy-direct-conflict");
        PolicyCommandTestSupport.writePolicyProject(projectDir);
        PolicyCommandTestSupport.writePolicyLockfile(projectDir);

        CommandResult result = execute(
                "check",
                "--check", "dependency-policy",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "ok dependency-policy demo Dependency policy baseline is explainable: 1 platform, 2 constraints, 3 exclusions, and 1 direct explicit version."));
        assertTrue(result.stdout().contains(
                "error dependency-policy [dependencyPolicy].exclude com.example:direct-lib Dependency policy excludes `com.example:direct-lib`, but that package is still a direct dependency."));
        assertTrue(result.stdout().contains(
                "next: Remove the direct dependency, or remove the exclusion if the dependency is intentional."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceDependencyPolicyPassesForSelectedMemberWithoutPolicy() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-dependency-policy-empty");
        Path apiDir = workspaceDir.resolve("apps/api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-dependency-policy-empty"
                members = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), CliTestSupport.memberConfig("api"));
        Files.writeString(workspaceDir.resolve("zolt.lock"), "version = 5\n");

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--check", "dependency-policy",
                "--cwd", workspaceDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "ok dependency-policy apps/api api Dependency policy baseline is explainable: 0 platforms, 0 constraints, 0 exclusions, and 0 direct explicit versions."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceDependencyPolicyReportsSelectedMemberConflicts() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-dependency-policy-direct-conflict");
        Path apiDir = workspaceDir.resolve("apps/api");
        PolicyCommandTestSupport.writePolicyProject(apiDir);
        PolicyCommandTestSupport.writePolicyLockfile(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-dependency-policy-direct-conflict"
                members = ["apps/api"]
                """);

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--check", "dependency-policy",
                "--cwd", workspaceDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "ok dependency-policy apps/api demo Dependency policy baseline is explainable: 1 platform, 2 constraints, 3 exclusions, and 1 direct explicit version."));
        assertTrue(result.stdout().contains(
                "error dependency-policy apps/api [dependencyPolicy].exclude com.example:direct-lib Dependency policy excludes `com.example:direct-lib`, but that package is still a direct dependency."));
        assertTrue(result.stdout().contains(
                "next: Remove the direct dependency, or remove the exclusion if the dependency is intentional."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceDependencyPolicyMalformedLockfileUsesWorkspaceRemediation() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-dependency-policy-malformed-lockfile");
        Path apiDir = workspaceDir.resolve("apps/api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-dependency-policy-malformed-lockfile"
                members = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), CliTestSupport.memberConfig("api"));
        Files.writeString(workspaceDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = 42
                """);

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--check", "dependency-policy",
                "--cwd", workspaceDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error dependency-policy zolt.lock"));
        assertTrue(result.stdout().contains("next: Run `zolt resolve --workspace`."));
        assertEquals("", result.stderr());
    }
}
