package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end cover for the root-lock freshness gate, driven exactly the way it goes wrong in the
 * field: a fresh clone against a cold cache, a lockfile-only edit from a botched merge, and an
 * unimportant config edit that must not condemn every later command to the full resolve.
 */
final class WorkspaceLockFreshnessCommandTest {
    private static final String LOCKED_VERSION = "1.0.0";

    @TempDir
    private Path tempDir;

    /**
     * The resolve the gate skips is also what materializes locked artifacts, so an evicted cache
     * entry has to send the command back to it rather than surface as a build-time integrity error.
     */
    @Test
    void buildRematerializesAnArtifactEvictedSinceTheLockWasWritten() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            writeWorkspace(repository, LOCKED_VERSION);
            assertEquals(0, resolve().exitCode());
            Files.delete(cachedJar(LOCKED_VERSION));

            CommandResult result = build();

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(Files.isRegularFile(cachedJar(LOCKED_VERSION)));
            assertTrue(result.stdout().contains("Compiled 1 main source files in apps/api"));
        }
    }

    @Test
    void offlineBuildOfAnEvictedArtifactFailsWithTheOfflineRemedy() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            writeWorkspace(repository, LOCKED_VERSION);
            assertEquals(0, resolve().exitCode());
            Files.delete(cachedJar(LOCKED_VERSION));

            CommandResult result = execute(
                    "build", "--workspace", "--all", "--offline",
                    "--cwd", apiDir().toString(),
                    "--cache-root", cacheRoot().toString());

            assertEquals(1, result.exitCode());
            assertTrue(
                    result.stderr().contains("Offline mode requires cached"),
                    result.stderr());
            assertFalse(result.stderr().contains("integrity check failed"), result.stderr());
        }
    }

    /**
     * The fingerprint lives inside the lock it certifies, so it has to cover that lock's own
     * content: a hand-edited package block is what the byte comparison used to catch.
     */
    @Test
    void buildRejectsALockWhosePackageBlockWasEditedByHand() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            writeWorkspace(repository, LOCKED_VERSION);
            assertEquals(0, resolve().exitCode());
            Files.writeString(lockfilePath(), swappedPackageBlock(repository));

            CommandResult rejected = build();

            assertEquals(1, rejected.exitCode());
            assertTrue(
                    rejected.stderr().contains("Workspace zolt.lock is out of date."),
                    rejected.stderr());
        }
    }

    @Test
    void buildAcceptsThatLockOnceResolveHasRewrittenIt() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            writeWorkspace(repository, LOCKED_VERSION);
            assertEquals(0, resolve().exitCode());
            Files.writeString(lockfilePath(), swappedPackageBlock(repository));

            assertEquals(0, resolve().exitCode());
            CommandResult result = build();

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(Files.readString(lockfilePath()).contains("version = \"" + LOCKED_VERSION));
        }
    }

    /**
     * A comment-only edit invalidates the fingerprint but not the lock, so the verification passes
     * — and the recomputed fingerprint has to be recorded or every later command repeats it.
     */
    @Test
    void aVerificationThatPassesLeavesTheNextCommandOnTheFastPath() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            writeWorkspace(repository, LOCKED_VERSION);
            assertEquals(0, resolve().exitCode());
            Path memberConfig = apiDir().resolve("zolt.toml");
            Files.writeString(memberConfig, Files.readString(memberConfig) + "\n# a thought\n");

            assertEquals("verified", freshness(timedBuild()));
            assertEquals("matched", freshness(timedBuild()));
        }
    }

    @Test
    void aLockWrittenBeforeTheFingerprintExistedIsUpgradedExactlyOnce() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            writeWorkspace(repository, LOCKED_VERSION);
            assertEquals(0, resolve().exitCode());
            Files.writeString(lockfilePath(), Files.readString(lockfilePath()).lines()
                    .filter(line -> !line.startsWith("workspaceResolutionInputFingerprint = "))
                    .reduce("", (left, right) -> left + right + "\n"));

            assertEquals("verified", freshness(timedBuild()));
            assertEquals("matched", freshness(timedBuild()));
            assertTrue(Files.readString(lockfilePath())
                    .contains("workspaceResolutionInputFingerprint = "));
        }
    }

    /**
     * Package blocks for another version, with their real hashes, under the header this config
     * produced — exactly what a botched merge or a lockfile-only pull request leaves behind. Nothing
     * about the configs changed, so only the lock's own content can give the edit away.
     */
    private String swappedPackageBlock(CliTestRepository repository) throws IOException {
        String current = Files.readString(lockfilePath());
        writeWorkspace(repository, "1.0.1");
        assertEquals(0, resolve().exitCode());
        String swapped = Files.readString(lockfilePath());
        writeWorkspace(repository, LOCKED_VERSION);
        Files.writeString(lockfilePath(), current);
        return withRecordedFingerprintOf(swapped, current);
    }

    private static String withRecordedFingerprintOf(String swapped, String current) {
        String key = "workspaceResolutionInputFingerprint = ";
        String recorded = current.lines()
                .filter(line -> line.startsWith(key))
                .findFirst()
                .orElseThrow();
        return swapped.lines()
                .map(line -> line.startsWith(key) ? recorded : line)
                .reduce("", (left, right) -> left + right + "\n");
    }

    private static String freshness(CommandResult result) {
        return result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"workspace lock freshness\""))
                .map(line -> line.substring(line.indexOf("\"workspaceLockFreshness\":\"")
                        + "\"workspaceLockFreshness\":\"".length()))
                .map(value -> value.substring(0, value.indexOf('"')))
                .findFirst()
                .orElseThrow();
    }

    private CommandResult resolve() {
        return execute(
                "resolve", "--workspace",
                "--cwd", apiDir().toString(),
                "--cache-root", cacheRoot().toString());
    }

    private CommandResult build() {
        return execute(
                "build", "--workspace", "--all",
                "--cwd", apiDir().toString(),
                "--cache-root", cacheRoot().toString());
    }

    private CommandResult timedBuild() {
        return execute(
                "build", "--workspace", "--all", "--timings", "--timings-format", "json",
                "--cwd", apiDir().toString(),
                "--cache-root", cacheRoot().toString());
    }

    private Path workspaceDir() {
        return tempDir.resolve("workspace");
    }

    private Path apiDir() {
        return workspaceDir().resolve("apps/api");
    }

    private Path lockfilePath() {
        return workspaceDir().resolve("zolt.lock");
    }

    private Path cacheRoot() {
        return tempDir.resolve("cache");
    }

    private Path cachedJar(String version) {
        return cacheRoot().resolve(
                "com/example/app/" + version + "/app-" + version + ".jar");
    }

    private void writeWorkspace(CliTestRepository repository, String version) throws IOException {
        repository.addArtifact("com.example", "app", version, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version));
        Files.createDirectories(apiDir());
        Files.writeString(workspaceDir().resolve("zolt.toml"), """
                [workspace]
                name = "workspace"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(repository.baseUri()));
        Files.writeString(apiDir().resolve("zolt.toml"), CliTestSupport.memberConfig("api") + """

                [dependencies]
                "com.example:app" = "%s"
                """.formatted(version));
        Path source = apiDir().resolve("src/main/java/com/example/api/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.api;

                public final class Api {
                }
                """);
    }
}
