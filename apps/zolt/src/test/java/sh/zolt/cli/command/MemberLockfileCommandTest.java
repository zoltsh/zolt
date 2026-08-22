package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * Design §4.5/§6.9: a workspace has exactly one authoritative {@code zolt.lock}, at its root, and no
 * command inspects a member-local one.
 *
 * <p>Every command here read {@code <member>/zolt.lock} before this was fixed, so from a member
 * directory it either failed outright or silently reported the workspace's locked state as absent.
 * Each case pins both halves: the root lock is the one read, and a file planted at the member-local
 * path is never consulted.
 */
final class MemberLockfileCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberClasspathReadsWorkspaceRootLock() throws IOException {
        Path member = workspace();

        CommandResult result = execute("classpath", "audit", "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("com.example:core:1.0.0"), result.stdout());
    }

    @Test
    void memberClasspathIgnoresMemberLocalLock() throws IOException {
        Path member = workspace();
        Files.writeString(member.resolve("zolt.lock"), poisonedLock());

        CommandResult result = execute("classpath", "audit", "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("com.example:core:1.0.0"), result.stdout());
        assertFalse(result.stdout().contains("poison"), result.stdout());
    }

    @Test
    void memberClasspathLaneQueryReadsWorkspaceRootLock() throws IOException {
        Path member = workspace();
        Files.writeString(member.resolve("zolt.lock"), poisonedLock());

        CommandResult result = execute("classpath", "compile", "--cwd", member.toString());

        assertFalse(
                result.stderr().contains(member.resolve("zolt.lock").toString()),
                () -> "the member-local lock must never be opened: " + result.stderr());
        assertFalse(result.stdout().contains("poison"), result.stdout());
    }

    @Test
    void memberToolchainListReadsWorkspaceRootLock() throws IOException {
        Path member = workspace();
        Files.writeString(member.resolve("zolt.lock"), poisonedLock());

        CommandResult result = execute("toolchain", "list", "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        // Only the workspace root's lock carries this entry; a member-local read reports `entries: 0`.
        assertTrue(result.stdout().contains("entries: 1"), result.stdout());
        assertTrue(result.stdout().contains("temurin 21.0.2"), result.stdout());
        assertFalse(result.stdout().contains("poison"), result.stdout());
    }

    @Test
    void memberToolchainStatusReadsWorkspaceRootLock() throws IOException {
        Path member = workspace();
        Files.writeString(member.resolve("zolt.lock"), poisonedLock());

        CommandResult result = execute("toolchain", "status", "--cwd", member.toString());

        // The locked entry lives only in the root lock, so finding it at all is the proof: a
        // member-local read reports the lock metadata as missing instead.
        String output = result.stdout() + result.stderr();
        assertTrue(output.contains("locked but not installed"), output);
        assertFalse(output.contains("lock metadata is missing"), output);
        assertFalse(output.contains("poison"), output);
    }

    /**
     * The plan reads the root lock. A stray member-local lock is not planted here: the build
     * freshness gate deliberately still notices one and redirects to `--workspace --member`, which is
     * its own pinned behavior.
     */
    @Test
    void memberPackagePlanReadsWorkspaceRootLock() throws IOException {
        Path member = workspace();

        CommandResult result = execute("package", "--plan", "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Package plan"), result.stdout());
    }

    /** Only the workspace root's lock exists; a member command must still find it. */
    private Path workspace() throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "temurin"
                policy = "require-managed"
                """);
        Files.writeString(root.resolve("zolt.lock"), """
                version = 7

                [[package]]
                id = "com.example:core"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "com/example/core/1.0.0/core-1.0.0.jar"
                dependencies = []

                [[toolchain.java]]
                id = "java-temurin-21"
                request.version = "21"
                request.distribution = "temurin"
                request.policy = "require-managed"
                platform.os = "%s"
                platform.arch = "%s"
                resolved.version = "21.0.2"
                resolved.distribution = "temurin"
                artifact.catalog = "builtin:java-temurin-21"
                artifact.uri = "https://example.test/temurin-21.tar.gz"
                artifact.sha256 = "%s"
                layout.javaHome = "."
                layout.executables.java = "bin/java"
                layout.executables.javac = "bin/javac"
                layout.executables.jar = "bin/jar"
                """.formatted(hostOs(), hostArch(), "0".repeat(64)));
        Path member = root.resolve("apps/api");
        Files.createDirectories(member.resolve("src/main/java"));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                """);
        return member;
    }

    private static String hostOs() {
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (name.contains("mac")) {
            return "macos";
        }
        return name.contains("win") ? "windows" : "linux";
    }

    private static String hostArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm") ? "aarch64" : "x64";
    }

    /** A member-local lock naming a coordinate no command may ever report. */
    private static String poisonedLock() {
        return """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:poison"
                version = "9.9.9"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:poison"
                version = "9.9.9"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/poison/9.9.9/poison-9.9.9.jar"
                dependencies = []
                """;
    }
}
