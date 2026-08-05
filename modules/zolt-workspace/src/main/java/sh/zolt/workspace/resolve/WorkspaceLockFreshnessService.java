package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
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
 * current inputs and its own content produce, <em>and</em> every artifact it names is already in the
 * cache; the command then continues without resolving anything. Otherwise — a changed input, a
 * hand-edited lock, a cold cache, or a lock written before the fingerprint existed — the previous
 * behaviour runs unchanged: a full locked workspace resolve, which materializes what the cache is
 * missing and fails with the existing actionable stale-lock error when the lock no longer matches.
 *
 * <p>A locked resolve that passes has proved the lock is derived from these inputs, so the gate
 * records the fingerprint it just computed. Without that, an input edit the verification forgives —
 * a comment — would leave every later command paying the full resolve forever.
 */
public final class WorkspaceLockFreshnessService {
    private final WorkspaceDiscoveryService workspaceDiscoveryService;
    private final LockedResolve lockedResolve;
    private final ZoltLockfileReader lockfileReader;

    /** The full locked workspace resolve the fast path exists to avoid. */
    @FunctionalInterface
    interface LockedResolve {
        void verify(Workspace workspace, Path cacheRoot, boolean offline, String retryCommand);
    }

    public WorkspaceLockFreshnessService(
            WorkspaceDiscoveryService workspaceDiscoveryService,
            WorkspaceResolveService workspaceResolveService) {
        this(
                workspaceDiscoveryService,
                (workspace, cacheRoot, offline, retryCommand) -> workspaceResolveService.resolve(
                        workspace, cacheRoot, true, offline, retryCommand),
                new ZoltLockfileReader());
    }

    WorkspaceLockFreshnessService(
            WorkspaceDiscoveryService workspaceDiscoveryService,
            LockedResolve lockedResolve,
            ZoltLockfileReader lockfileReader) {
        this.workspaceDiscoveryService = workspaceDiscoveryService;
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
                workspaceDiscoveryService.discover(workingDirectory.toAbsolutePath().normalize());
        long discoveryNanos = Math.max(0L, System.nanoTime() - discoveryStarted);
        if (discovered.isEmpty()) {
            return Optional.empty();
        }
        Workspace workspace = discovered.orElseThrow();
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        Optional<String> content = readLockfile(lockfilePath);
        if (content.isEmpty() || !looksGeneratedLockfile(content.orElseThrow())) {
            return Optional.of(freshness(
                    workspace,
                    lockfilePath,
                    Optional.empty(),
                    WorkspaceLockFreshness.Outcome.NOT_GENERATED,
                    discoveryNanos));
        }
        Optional<ZoltLockfile> lockfile = parse(content.orElseThrow());
        if (skippable(workspace, lockfile, content.orElseThrow(), cacheRoot)) {
            return Optional.of(freshness(
                    workspace,
                    lockfilePath,
                    lockfile,
                    WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED,
                    discoveryNanos));
        }
        lockedResolve.verify(workspace, cacheRoot, offline, retryCommand);
        WorkspaceLockFingerprintRecorder.record(workspace, lockfilePath);
        return Optional.of(freshness(
                workspace,
                lockfilePath,
                lockfile,
                WorkspaceLockFreshness.Outcome.VERIFIED,
                discoveryNanos));
    }

    /**
     * Whether the locked resolve can be skipped. A matching fingerprint alone is not enough: that
     * resolve is also the only step that materializes locked artifacts, so a cache missing any of
     * them still needs it — and under {@code --offline} it is the resolve that reports the missing
     * artifact with the actionable offline error rather than reaching the network.
     */
    private boolean skippable(
            Workspace workspace,
            Optional<ZoltLockfile> lockfile,
            String content,
            Path cacheRoot) {
        return matchesResolutionInputs(workspace, lockfile, content)
                && WorkspaceLockArtifactPresence.complete(lockfile.orElseThrow(), cacheRoot);
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
            long discoveryNanos) {
        return new WorkspaceLockFreshness(
                workspace, lockfilePath, lockfile, outcome, discoveryNanos);
    }

    /** Absent rather than fatal for an unreadable lock, so the full resolve reports the problem. */
    private Optional<ZoltLockfile> parse(String content) {
        try {
            return Optional.of(lockfileReader.read(content));
        } catch (LockfileReadException exception) {
            return Optional.empty();
        }
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

    private static boolean looksGeneratedLockfile(String content) {
        return content.lines().anyMatch(line ->
                line.contains("Sha256 = ")
                        || line.contains("aliasFingerprint = ")
                        || line.contains("projectResolutionFingerprint = "));
    }
}
