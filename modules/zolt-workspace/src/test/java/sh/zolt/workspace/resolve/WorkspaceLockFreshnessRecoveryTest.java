package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.lockfile.toml.LockfileSidecars;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three ways a fingerprint match on its own is not enough to skip the locked resolve: the cache
 * it assumes may be gone, the lock it certifies may have been edited underneath it, and a
 * verification that passes has to be recorded or the slow path never ends.
 */
final class WorkspaceLockFreshnessRecoveryTest {
    private static final String LOCKED_VERSION = "2.0.17";

    private final List<String> verifications = new ArrayList<>();

    @Test
    void skipsWhenEveryArtifactTheLockNamesIsPresent(@TempDir Path root) throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED, freshness.outcome());
        assertEquals(List.of(), verifications);
    }

    /** The skipped resolve is the only step that materializes locked artifacts. */
    @Test
    void resolvesWhenAMatchingLockNamesAJarTheCacheNoLongerHas(@TempDir Path root)
            throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        Files.delete(cachedArtifact(root, LOCKED_VERSION, "jar"));

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, freshness.outcome());
        assertEquals(List.of("verify:offline=false"), verifications);
    }

    @Test
    void resolvesWhenAMatchingLockNamesAPomTheCacheNoLongerHas(@TempDir Path root)
            throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        Files.delete(cachedArtifact(root, LOCKED_VERSION, "pom"));

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, requireFresh(root).outcome());
    }

    /** Offline is carried into that resolve, which is what reports the missing artifact. */
    @Test
    void handsTheOfflineFlagToTheResolveItFallsBackTo(@TempDir Path root) throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        Files.delete(cachedArtifact(root, LOCKED_VERSION, "jar"));

        service().requireFresh(root, cacheRoot(root), true, "zolt build --workspace");

        assertEquals(List.of("verify:offline=true"), verifications);
    }

    /**
     * A botched merge or a lockfile-only edit leaves a self-consistent package block that no config
     * change explains. The fingerprint covers the lock's own content precisely so this cannot pass.
     */
    @Test
    void refusesToSkipForAPackageBlockEditedToADifferentVersion(@TempDir Path root)
            throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        Path lockfilePath = root.resolve("zolt.lock");
        Files.writeString(
                lockfilePath, Files.readString(lockfilePath).replace(LOCKED_VERSION, "2.0.16"));
        cacheArtifacts(root, "2.0.16");

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, freshness.outcome());
        assertFalse(freshness.resolutionSkipped());
    }

    @Test
    void skipsAgainOnceResolveRewritesTheLockAndItsFingerprint(@TempDir Path root)
            throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        writeWorkspace(root, "2.0.16");
        writeCurrentLockfile(root);

        assertEquals(
                WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED,
                requireFresh(root).outcome());
    }

    /**
     * A comment-only config edit invalidates the fingerprint but not the lock, so the verification
     * passes — and must leave the recomputed fingerprint behind, or every later command repeats it.
     */
    @Test
    void recordsTheFingerprintAVerificationJustProvedSoTheNextCommandSkips(@TempDir Path root)
            throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        String before = recorded(root);
        appendComment(root);

        WorkspaceLockFreshness first = requireFresh(root);
        WorkspaceLockFreshness second = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, first.outcome());
        assertEquals(WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED, second.outcome());
        assertEquals(List.of("verify:offline=false"), verifications);
        assertNotEquals(before, recorded(root));
    }

    @Test
    void upgradesALockWrittenBeforeTheFingerprintExistedExactlyOnce(@TempDir Path root)
            throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        Files.writeString(root.resolve("zolt.lock"), lockBody(LOCKED_VERSION));

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, requireFresh(root).outcome());
        assertEquals(
                WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED,
                requireFresh(root).outcome());
        assertEquals(List.of("verify:offline=false"), verifications);
    }

    /** Recording is an optimisation, so a lock the process may not write must not fail a command. */
    @Test
    void keepsWorkingWhenTheLockCannotBeRewritten(@TempDir Path root) throws IOException {
        writeWorkspace(root, LOCKED_VERSION);
        writeCurrentLockfile(root);
        appendComment(root);
        String before = Files.readString(root.resolve("zolt.lock"));
        try {
            assumeTrue(readOnly(root), "read-only directories are unavailable here");
            assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, requireFresh(root).outcome());
            assertEquals(before, Files.readString(root.resolve("zolt.lock")));
        } finally {
            writable(root);
        }
    }

    /** True only once the directory actually refuses a write, so a root-run suite skips instead. */
    private static boolean readOnly(Path root) {
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
        Path probe = root.resolve("probe");
        try {
            Files.writeString(probe, "probe");
            Files.delete(probe);
            return false;
        } catch (IOException exception) {
            return true;
        }
    }

    private static void writable(Path root) {
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException | UnsupportedOperationException exception) {
            // The temporary directory is already writable, or permissions are not supported here.
        }
    }

    private WorkspaceLockFreshness requireFresh(Path root) {
        return service()
                .requireFresh(root, cacheRoot(root), false, "zolt build --workspace")
                .orElseThrow();
    }

    private WorkspaceLockFreshnessService service() {
        return new WorkspaceLockFreshnessService(
                new WorkspaceDiscoveryService(),
                (workspace, cacheRoot, offline, retryCommand) ->
                        verifications.add("verify:offline=" + offline),
                new ZoltLockfileReader());
    }

    private static String recorded(Path root) throws IOException {
        return new ZoltLockfileReader()
                .read(Files.readString(root.resolve("zolt.lock")))
                .workspaceResolutionInputFingerprint()
                .orElseThrow();
    }

    private static void appendComment(Path root) throws IOException {
        Path memberConfig = root.resolve("lib").resolve("zolt.toml");
        Files.writeString(memberConfig, Files.readString(memberConfig) + "\n# a passing thought\n");
    }

    /** The lock and cache an ordinary {@code zolt resolve --workspace} would leave behind. */
    private static void writeCurrentLockfile(Path root) throws IOException {
        String version = declaredVersion(root);
        String body = lockBody(version);
        Files.writeString(
                root.resolve("zolt.lock"),
                LockfileSidecars.withWorkspaceResolutionInputFingerprint(
                        body,
                        WorkspaceResolutionInputFingerprint
                                .fingerprint(new WorkspaceDiscoveryService().load(root), body)
                                .orElseThrow()));
        cacheArtifacts(root, version);
    }

    private static void cacheArtifacts(Path root, String version) throws IOException {
        for (String extension : List.of("jar", "pom")) {
            Path artifact = cachedArtifact(root, version, extension);
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, extension);
        }
    }

    private static String declaredVersion(Path root) throws IOException {
        return Files.readString(root.resolve("lib").resolve("zolt.toml"))
                .lines()
                .filter(line -> line.startsWith("\"org.slf4j:slf4j-api\""))
                .map(line -> line.substring(line.indexOf('"', line.indexOf('=')) + 1, line.length() - 1))
                .findFirst()
                .orElseThrow();
    }

    private static Path cacheRoot(Path root) {
        return root.resolve("cache");
    }

    private static Path cachedArtifact(Path root, String version, String extension) {
        String digest = extension.equals("jar") ? "1".repeat(64) : "2".repeat(64);
        return cacheRoot(root).resolve(
                "blobs/v2/sha256/" + digest + "/slf4j-api-" + version + "." + extension);
    }

    private static String lockBody(String version) {
        return """
                version = 6
                projectResolutionFingerprint = "sha256:abc"

                [[package]]
                id = "org.slf4j:slf4j-api"
                version = "%1$s"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/1111111111111111111111111111111111111111111111111111111111111111/slf4j-api-%1$s.jar"
                pom = "blobs/v2/sha256/2222222222222222222222222222222222222222222222222222222222222222/slf4j-api-%1$s.pom"
                jarSha256 = "1111111111111111111111111111111111111111111111111111111111111111"
                pomSha256 = "2222222222222222222222222222222222222222222222222222222222222222"
                members = ["lib"]
                dependencies = []
                """.formatted(version);
    }

    private static void writeWorkspace(Path root, String version) throws IOException {
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "demo"
                members = ["lib"]
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib").resolve("zolt.toml"), """
                [project]
                name = "lib"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "org.slf4j:slf4j-api" = "%s"
                """.formatted(version));
    }
}
