package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * Design §4.5, for the two toolchain commands with no authored request to fall back on: {@code zolt
 * exec} and {@code zolt toolchain list}, started in a workspace member whose effective
 * {@code [toolchain.java]} is absent both locally and by inheritance, resolve ambient Java without
 * ever opening {@code <member>/zolt.lock}.
 *
 * <p>{@link MemberLockfileCommandTest} pins the same invariant for the members that DO inherit a
 * workspace toolchain. This file covers the gap that inheritance hid: with nothing authored anywhere,
 * both commands loaded the effective config and evaluated the toolchain with the member directory as
 * its own lock root, so whatever sat at the member-local path was parsed and validated before ambient
 * Java was ever reached.
 *
 * <p>The planted file is MALFORMED on purpose. A valid-but-poisoned lock only proves a command did not
 * USE the member's lock; bytes that are not TOML at all cannot be parsed, so surviving them proves the
 * command never OPENED it. Each case also asserts the file is byte-identical afterwards: not consumed,
 * not rewritten, not repaired.
 */
final class MemberToolchainCommandLockProjectionTest {
    /** Not TOML. Reading this file at all fails, which is exactly what makes it evidence. */
    private static final String MALFORMED_LOCK = "not a lockfile";

    @TempDir
    private Path tempDir;

    @Test
    void memberExecWithoutToolchainIgnoresMalformedMemberLock() throws IOException {
        Path member = member();
        Path memberLock = member.resolve("zolt.lock");
        Files.writeString(memberLock, MALFORMED_LOCK);

        CommandResult result = exec(member);

        assertMemberLockNeverOpened(result, memberLock);
        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertEquals(
                MALFORMED_LOCK,
                Files.readString(memberLock),
                "exec consumed or rewrote the member-local lock");
    }

    @Test
    void memberToolchainListWithoutToolchainIgnoresMalformedMemberLock() throws IOException {
        Path member = member();
        Path memberLock = member.resolve("zolt.lock");
        Files.writeString(memberLock, MALFORMED_LOCK);

        CommandResult result = toolchainList(member);

        assertMemberLockNeverOpened(result, memberLock);
        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertEquals(
                MALFORMED_LOCK,
                Files.readString(memberLock),
                "toolchain list consumed or rewrote the member-local lock");
    }

    /**
     * The reviewer's repro with nothing planted: no request is authored anywhere and no lock exists at
     * either root, so the only possible answer is ambient Java — and the command must not invent a
     * member-local lock on the way there.
     */
    @Test
    void memberExecUsesAmbientJavaWhenNoToolchainIsAuthored() throws IOException {
        Path member = member();

        CommandResult result = exec(member);

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(
                (result.stdout() + result.stderr()).toLowerCase(Locale.ROOT).contains("version"),
                () -> "exec did not run java on the ambient toolchain: "
                        + result.stdout() + result.stderr());
        assertFalse(
                Files.exists(member.resolve("zolt.lock")),
                "exec created a member-local lock");
    }

    /**
     * Both halves of the listing answer from the workspace root: the "project lock" section reports the
     * root's one Java entry, and the active status beside it resolves without touching the malformed
     * file at the member-local path. A member-local read would report {@code entries: 0} — or, with
     * these bytes, fail outright.
     */
    @Test
    void memberToolchainListReadsOnlyTheRootLock() throws IOException {
        Path member = member();
        Path memberLock = member.resolve("zolt.lock");
        Files.writeString(member.getParent().getParent().resolve("zolt.lock"), rootLock());
        Files.writeString(memberLock, MALFORMED_LOCK);

        CommandResult result = toolchainList(member);

        assertMemberLockNeverOpened(result, memberLock);
        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("entries: 1"), result.stdout());
        assertTrue(result.stdout().contains("temurin 21.0.2"), result.stdout());
        assertEquals(
                MALFORMED_LOCK,
                Files.readString(memberLock),
                "toolchain list consumed or rewrote the member-local lock");
    }

    private CommandResult exec(Path member) {
        return execute(
                "exec",
                "--directory", member.toString(),
                "--global-config", tempDir.resolve("global/config.toml").toString(),
                "--toolchain-install-root", tempDir.resolve("toolchains").toString(),
                "--",
                "java",
                "-version");
    }

    private CommandResult toolchainList(Path member) {
        return execute(
                "toolchain",
                "list",
                "--directory", member.toString(),
                "--config", tempDir.resolve("global/config.toml").toString(),
                "--install-root", tempDir.resolve("toolchains").toString());
    }

    /**
     * The failure this pins is a READ, not a wrong answer, so it is asserted on the diagnostics: the
     * lockfile parser's message and the member-local path itself must appear nowhere in the output.
     */
    private static void assertMemberLockNeverOpened(CommandResult result, Path memberLock) {
        String output = result.stdout() + result.stderr();
        assertFalse(
                output.contains("Could not parse zolt.lock"),
                () -> "the member-local lock was parsed: " + output);
        assertFalse(
                output.contains(memberLock.toString()),
                () -> "the member-local lock was opened: " + output);
    }

    /**
     * The reviewer's repro shape: a workspace root that shares an identity but authors no
     * {@code [toolchain.java]}, and a member manifest carrying nothing but its name.
     */
    private Path member() throws IOException {
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
                """);
        Path member = root.resolve("apps/api");
        Files.createDirectories(member.resolve("src/main/java"));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                """);
        return member;
    }

    /** The workspace's one authoritative lock, carrying the only Java entry in the fixture. */
    private static String rootLock() {
        return """
                version = 7

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
                """.formatted(hostOs(), hostArch(), "0".repeat(64));
    }

    private static String hostOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return "macos";
        }
        return name.contains("win") ? "windows" : "linux";
    }

    private static String hostArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm") ? "aarch64" : "x64";
    }
}
