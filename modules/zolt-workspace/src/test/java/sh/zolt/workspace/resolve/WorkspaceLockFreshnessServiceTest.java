package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.toml.LockfileSidecars;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.service.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gate must skip the locked resolve only when the recorded fingerprint proves the lock matches
 * its inputs, and must fall back to that resolve in every other case.
 */
final class WorkspaceLockFreshnessServiceTest {
    private final List<String> verifications = new ArrayList<>();

    @Test
    void skipsTheLockedResolveWhenTheRecordedFingerprintMatches(@TempDir Path root) throws IOException {
        writeWorkspace(root);
        writeLockfile(root, Optional.of(currentFingerprint(root)));

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED, freshness.outcome());
        assertTrue(freshness.resolutionSkipped());
        assertEquals(List.of(), verifications);
    }

    @Test
    void returnsTheDiscoveredWorkspaceSoPlanningNeedNotRediscoverIt(@TempDir Path root) throws IOException {
        writeWorkspace(root);
        writeLockfile(root, Optional.of(currentFingerprint(root)));

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(
                List.of("app", "lib"),
                freshness.workspace().members().stream().map(m -> m.path()).sorted().toList());
        assertEquals(root.resolve("zolt.lock"), freshness.lockfilePath());
        assertTrue(freshness.lockfile().isPresent());
    }

    /** Discovery moved here from planning, so this is the only place its cost can be measured. */
    @Test
    void reportsWhatTheOneDiscoveryCost(@TempDir Path root) throws IOException {
        writeWorkspace(root);
        writeLockfile(root, Optional.of(currentFingerprint(root)));

        assertTrue(requireFresh(root).discoveryNanos() > 0L);
    }

    @Test
    void runsTheLockedResolveWhenAnInputChanged(@TempDir Path root) throws IOException {
        writeWorkspace(root);
        writeLockfile(root, Optional.of(currentFingerprint(root)));
        Files.writeString(
                root.resolve("lib").resolve("zolt.toml"),
                Files.readString(root.resolve("lib").resolve("zolt.toml"))
                        .replace("2.0.17", "2.0.16"));

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, freshness.outcome());
        assertEquals(List.of("verify:zolt build --workspace"), verifications);
    }

    @Test
    void runsTheLockedResolveOnceForALockWithoutAFingerprint(@TempDir Path root) throws IOException {
        writeWorkspace(root);
        writeLockfile(root, Optional.empty());

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, freshness.outcome());
        assertEquals(List.of("verify:zolt build --workspace"), verifications);
    }

    @Test
    void runsTheLockedResolveWhenTheRecordedFingerprintIsFromOtherInputs(@TempDir Path root) throws IOException {
        writeWorkspace(root);
        writeLockfile(root, Optional.of("sha256:0000000000000000000000000000000000000000000000000000000000000000"));

        assertEquals(WorkspaceLockFreshness.Outcome.VERIFIED, requireFresh(root).outcome());
        assertEquals(List.of("verify:zolt build --workspace"), verifications);
    }

    @Test
    void doesNothingWhenThereIsNoGeneratedLockfile(@TempDir Path root) throws IOException {
        writeWorkspace(root);

        WorkspaceLockFreshness freshness = requireFresh(root);

        assertEquals(WorkspaceLockFreshness.Outcome.NOT_GENERATED, freshness.outcome());
        assertEquals(List.of(), verifications);
    }

    @Test
    void doesNothingForALockfileThatWasNotGeneratedByResolve(@TempDir Path root) throws IOException {
        writeWorkspace(root);
        Files.writeString(root.resolve("zolt.lock"), "version = 5\n");

        assertEquals(WorkspaceLockFreshness.Outcome.NOT_GENERATED, requireFresh(root).outcome());
        assertEquals(List.of(), verifications);
    }

    @Test
    void leavesNonWorkspaceDirectoriesAlone(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("zolt.toml"), """
                [project]
                name = "solo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);

        assertTrue(service().requireFresh(root, root.resolve("cache"), false, "zolt build --workspace").isEmpty());
        assertEquals(List.of(), verifications);
    }

    private WorkspaceLockFreshness requireFresh(Path root) {
        return service()
                .requireFresh(root, root.resolve("cache"), false, "zolt build --workspace")
                .orElseThrow();
    }

    private WorkspaceLockFreshnessService service() {
        return new WorkspaceLockFreshnessService(
                new WorkspaceDiscoveryService(),
                (workspace, cacheRoot, offline, retryCommand) ->
                        verifications.add("verify:" + retryCommand),
                new ZoltLockfileReader());
    }

    private static String currentFingerprint(Path root) {
        Workspace workspace = new WorkspaceDiscoveryService().load(root);
        return WorkspaceResolutionInputFingerprint
                .fingerprint(workspace, lockBody())
                .orElseThrow();
    }

    private static void writeLockfile(Path root, Optional<String> fingerprint) throws IOException {
        Files.writeString(root.resolve("zolt.lock"), fingerprint
                .map(value -> LockfileSidecars
                        .withWorkspaceResolutionInputFingerprint(lockBody(), value))
                .orElseGet(WorkspaceLockFreshnessServiceTest::lockBody));
    }

    private static String lockBody() {
        return """
                version = 5
                projectResolutionFingerprint = "sha256:abc"
                """;
    }

    private static void writeWorkspace(Path root) throws IOException {
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "demo"
                members = ["lib", "app"]
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib").resolve("zolt.toml"), """
                [project]
                name = "lib"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app").resolve("zolt.toml"), """
                [project]
                name = "app"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "com.example:lib" = { workspace = "lib" }
                """);
    }
}
