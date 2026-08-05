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
 * <p>The workspace is discovered once and the root lock is read once. When the lock records a
 * {@link WorkspaceResolutionInputFingerprint} equal to the one its current inputs produce, the lock
 * is current by construction and the command continues without resolving anything. Otherwise — a
 * changed input, or a lock written before the fingerprint existed — the previous behaviour runs
 * unchanged: a full locked workspace resolve, which fails with the existing actionable stale-lock
 * error when the lock no longer matches.
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
        Optional<Workspace> discovered =
                workspaceDiscoveryService.discover(workingDirectory.toAbsolutePath().normalize());
        if (discovered.isEmpty()) {
            return Optional.empty();
        }
        Workspace workspace = discovered.orElseThrow();
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        Optional<String> content = readLockfile(lockfilePath);
        if (content.isEmpty() || !looksGeneratedLockfile(content.orElseThrow())) {
            return Optional.of(new WorkspaceLockFreshness(
                    workspace,
                    lockfilePath,
                    Optional.empty(),
                    WorkspaceLockFreshness.Outcome.NOT_GENERATED));
        }
        Optional<ZoltLockfile> lockfile = parse(content.orElseThrow());
        if (matchesResolutionInputs(workspace, lockfile)) {
            return Optional.of(new WorkspaceLockFreshness(
                    workspace,
                    lockfilePath,
                    lockfile,
                    WorkspaceLockFreshness.Outcome.FINGERPRINT_MATCHED));
        }
        lockedResolve.verify(workspace, cacheRoot, offline, retryCommand);
        return Optional.of(new WorkspaceLockFreshness(
                workspace,
                lockfilePath,
                lockfile,
                WorkspaceLockFreshness.Outcome.VERIFIED));
    }

    private boolean matchesResolutionInputs(
            Workspace workspace,
            Optional<ZoltLockfile> lockfile) {
        Optional<String> recorded = lockfile
                .flatMap(ZoltLockfile::workspaceResolutionInputFingerprint);
        if (recorded.isEmpty()) {
            return false;
        }
        return WorkspaceResolutionInputFingerprint.fingerprint(workspace)
                .filter(recorded.orElseThrow()::equals)
                .isPresent();
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
