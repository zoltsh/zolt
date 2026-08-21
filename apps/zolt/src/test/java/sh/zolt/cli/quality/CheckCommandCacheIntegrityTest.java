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

final class CheckCommandCacheIntegrityTest extends CheckCommandTestSupport {
    @Test
    void checkCacheIntegrityReportsCorruptedLockedArtifact() throws IOException {
        Path projectDir = createProject("check-cache-integrity");
        Path cacheRoot = tempDir.resolve("cache-integrity-cache");
        String checksum = "0".repeat(64);
        Path jar = cacheRoot.resolve("blobs/v2/sha256/" + checksum + "/runtime-lib-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "corrupted runtime jar bytes");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:runtime-lib"
                version = "1.0.0"
                lane = "runtime"
                resolvedScope = "runtime"

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "blobs/v2/sha256/%s/runtime-lib-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(checksum, checksum));

        CommandResult result = execute(
                "check",
                "--check", "cache-integrity",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "error cache-integrity zolt.lock Cached jar integrity check failed for com.example:runtime-lib:1.0.0"));
        assertTrue(result.stdout().contains("next: Remove the cache entry or run `zolt resolve`."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkCacheIntegrityMalformedLockfileUsesLockfileRemediation() throws IOException {
        Path projectDir = createProject("check-cache-integrity-malformed-lockfile");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[package]]
                id = 42
                """);

        CommandResult result = execute(
                "check",
                "--check", "cache-integrity",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error cache-integrity zolt.lock Invalid value type in zolt.lock"));
        assertTrue(result.stdout().contains("next: Run `zolt resolve` to regenerate zolt.lock."));
        assertFalse(result.stdout().contains("Remove the cache entry"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceCacheIntegrityMissingLockfileUsesWorkspaceRemediation() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-cache-integrity-missing-lockfile");
        Path apiDir = workspaceDir.resolve("apps/api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-cache-integrity-missing-lockfile"

                [workspace.members]
                include = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--check", "cache-integrity",
                "--cwd", workspaceDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error cache-integrity zolt.lock zolt.lock is missing."));
        assertTrue(result.stdout().contains("next: Run `zolt resolve --workspace`."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceCacheIntegrityMalformedLockfileUsesWorkspaceRemediation() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-cache-integrity-malformed-lockfile");
        Path apiDir = workspaceDir.resolve("apps/api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-cache-integrity-malformed-lockfile"

                [workspace.members]
                include = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(workspaceDir.resolve("zolt.lock"), """
                version = 7

                [[package]]
                id = 42
                """);

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "apps/api",
                "--check", "cache-integrity",
                "--cwd", workspaceDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error cache-integrity zolt.lock Invalid value type in zolt.lock"));
        assertTrue(result.stdout().contains("next: Run `zolt resolve --workspace` to regenerate zolt.lock."));
        assertFalse(result.stdout().contains("Remove the cache entry"));
        assertEquals("", result.stderr());
    }
}
