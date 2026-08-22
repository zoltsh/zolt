package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.ContentAddressedLockCapability;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides whether a workspace root lock still matches its resolution inputs.
 *
 * <p>The workspace is discovered once and the root lock is read once. The lock is current by
 * construction when it records a {@link WorkspaceResolutionInputFingerprint} equal to the one its
 * current inputs and its own content produce, <em>and</em> every artifact it names matches its locked
 * checksum; the command then continues without resolving anything. Otherwise — a changed input, a
 * hand-edited lock, a cold or corrupt cache, or a lock written before the fingerprint existed — the
 * previous behaviour runs unchanged: a full locked workspace resolve, which repairs materialized
 * artifacts and fails with the existing actionable stale-lock error when the lock no longer matches.
 *
 * <p>A locked resolve that passes has proved the lock is derived from these inputs, so the gate
 * records the fingerprint it just computed. Without that, an input edit the verification forgives —
 * a comment — would leave every later command paying the full resolve forever.
 */
public final class WorkspaceLockFreshnessService {
    private final ManifestWorkspaceLoader workspaceLoader;
    private final LockedResolve lockedResolve;
    private final ZoltLockfileReader lockfileReader;

    /** The full locked workspace resolve the fast path exists to avoid. */
    @FunctionalInterface
    interface LockedResolve {
        void verify(Workspace workspace, Path cacheRoot, boolean offline, String retryCommand);
    }

    public WorkspaceLockFreshnessService(
            ManifestWorkspaceLoader workspaceLoader,
            WorkspaceResolveService workspaceResolveService) {
        this(
                workspaceLoader,
                (workspace, cacheRoot, offline, retryCommand) -> workspaceResolveService.resolve(
                        workspace, cacheRoot, true, offline, retryCommand),
                new ZoltLockfileReader());
    }

    WorkspaceLockFreshnessService(
            ManifestWorkspaceLoader workspaceLoader,
            LockedResolve lockedResolve,
            ZoltLockfileReader lockfileReader) {
        this.workspaceLoader = workspaceLoader;
        this.lockedResolve = lockedResolve;
        this.lockfileReader = lockfileReader;
    }

    /**
     * Empty when {@code workingDirectory} is not inside a workspace, which leaves single-project
     * commands alone. Otherwise the lock is current on return, or the call threw.
     */
    public Optional<WorkspaceLockFreshness> requireFresh(
            Path workingDirectory,
            Path cacheRoot,
            boolean offline,
            String retryCommand) {
        long discoveryStarted = System.nanoTime();
        Optional<Workspace> discovered =
                workspaceLoader.discover(workingDirectory.toAbsolutePath().normalize());
        long discoveryNanos = Math.max(0L, System.nanoTime() - discoveryStarted);
        if (discovered.isEmpty()) {
            return Optional.empty();
        }
        Workspace workspace = discovered.orElseThrow();
        VerifiedArtifactIndex artifactIndex = new VerifiedArtifactIndex();
        Path lockfilePath = ProjectLockfile.in(workspace.root());
        Optional<String> content = readLockfile(lockfilePath);
        if (content.isEmpty()) {
            return Optional.of(freshness(
                    workspace,
                    lockfilePath,
                    Optional.empty(),
                    WorkspaceLockFreshness.Outcome.NOT_GENERATED,
                    discoveryNanos,
                    artifactIndex));
        }
        Optional<ZoltLockfile> lockfile = parse(content.orElseThrow());
        lockfile.ifPresent(value -> ContentAddressedLockCapability.requireExecutableLockfile(
                value,
                "zolt resolve --workspace"));
        if (skippable(workspace, lockfile, content.orElseThrow(), cacheRoot, artifactIndex)) {
            return Optional.of(freshness(
                    workspace,
                    lockfilePath,
                    lockfile,
                    WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED,
                    discoveryNanos,
                    artifactIndex));
        }
        artifactIndex.invalidateAll();
        lockedResolve.verify(workspace, cacheRoot, offline, retryCommand);
        WorkspaceLockFingerprintRecorder.record(workspace, lockfilePath);
        ZoltLockfile verifiedLockfile = lockfileReader.read(lockfilePath);
        ContentAddressedLockCapability.requireExecutableLockfile(
                verifiedLockfile,
                "zolt resolve --workspace");
        artifactIndex.invalidateAll();
        WorkspaceLockArtifactIntegrity.verify(verifiedLockfile, cacheRoot, artifactIndex);
        return Optional.of(freshness(
                workspace,
                lockfilePath,
                Optional.of(verifiedLockfile),
                WorkspaceLockFreshness.Outcome.VERIFIED,
                discoveryNanos,
                artifactIndex));
    }

    /**
     * Whether the locked resolve can be skipped. A matching fingerprint alone is not enough: that
     * resolve is also the only step that materializes locked artifacts, so a cache missing or
     * corrupt copy still needs it — and under {@code --offline} it is the resolve that reports the
     * artifact with the actionable offline error rather than reaching the network.
     */
    private boolean skippable(
            Workspace workspace,
            Optional<ZoltLockfile> lockfile,
            String content,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        return matchesResolutionInputs(workspace, lockfile, content)
                && WorkspaceLockArtifactIntegrity.valid(
                        lockfile.orElseThrow(), cacheRoot, artifactIndex);
    }

    private boolean matchesResolutionInputs(
            Workspace workspace,
            Optional<ZoltLockfile> lockfile,
            String content) {
        Optional<String> recorded = lockfile
                .flatMap(ZoltLockfile::workspaceResolutionInputFingerprint);
        if (recorded.isEmpty()) {
            return false;
        }
        return WorkspaceResolutionInputFingerprint.fingerprint(workspace, content)
                .filter(recorded.orElseThrow()::equals)
                .isPresent();
    }

    private static WorkspaceLockFreshness freshness(
            Workspace workspace,
            Path lockfilePath,
            Optional<ZoltLockfile> lockfile,
            WorkspaceLockFreshness.Outcome outcome,
            long discoveryNanos,
            VerifiedArtifactIndex artifactIndex) {
        return new WorkspaceLockFreshness(
                workspace, lockfilePath, lockfile, outcome, discoveryNanos, artifactIndex);
    }

    /** Existing lock bytes must decode before any locked resolve, cache, or network work begins. */
    private Optional<ZoltLockfile> parse(String content) {
        return Optional.of(lockfileReader.read(content));
    }

    private static Optional<String> readLockfile(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(
                    Files.readAllBytes(lockfilePath), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw LockfileReadException.actionable(
                    "Could not read zolt.lock at " + lockfilePath + " while checking lockfile freshness.",
                    "Check that the file exists and is readable.",
                    exception);
        }
    }

}
