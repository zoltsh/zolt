package sh.zolt.workspace.resolve;

import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.service.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The root lock a workspace resolve would have written, when it is already the lock on disk.
 *
 * <p>A workspace resolve of unchanged inputs is a byte-identical rewrite: it resolves every member,
 * mediates, aggregates, and commits exactly the file it started from. The recorded
 * {@link WorkspaceResolutionInputFingerprint} certifies both the inputs and the lock's own content,
 * so when it still matches, that whole pass is provably redundant and the command reports the lock
 * as current instead of rewriting it. This is the same gate {@code zolt build} has taken since the
 * fingerprint was introduced; standalone {@code zolt resolve --workspace} simply did not consult it.
 *
 * <p>Artifact integrity is proved as well: the resolve is also what repairs locked artifacts, so a
 * cold, evicted, or corrupt cache still needs it.
 *
 * <p>Any change forces the full resolve, because any change breaks the digest: an edit to any
 * captured config file (a comment is enough), a member added or removed, a changed coordinate, a
 * hand-edited {@code [[package]]} block, a lock written before the fingerprint existed, or the
 * fingerprint line deleted. That deletion is the escape hatch — there is no flag to add, and none is
 * wanted, because a flag would be a second way to say what touching the inputs already says.
 */
final class WorkspaceResolveUpToDateLock {
    private WorkspaceResolveUpToDateLock() {
    }

    /**
     * The committed lock when it is current for {@code workspace}, empty when a resolve must run.
     * Requires the captured configs to still match disk first: the fingerprint is computed over that
     * snapshot, so a stale snapshot could certify a lock the current configs would not produce.
     */
    static Optional<WorkspaceResolveSnapshot> committed(
            Workspace workspace,
            Path lockfilePath,
            Path cacheRoot,
            ZoltLockfileReader lockfileReader) {
        Optional<String> content = readLockfile(lockfilePath);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        workspace.inputs().requireCurrent();
        Optional<ZoltLockfile> lockfile = parse(lockfileReader, content.orElseThrow());
        if (lockfile.isEmpty() || !matches(workspace, lockfile.orElseThrow(), content.orElseThrow())) {
            return Optional.empty();
        }
        if (!WorkspaceLockArtifactIntegrity.valid(
                lockfile.orElseThrow(), cacheRoot, new VerifiedArtifactIndex())) {
            return Optional.empty();
        }
        return Optional.of(new WorkspaceResolveSnapshot(
                new ResolveResult(
                        lockfile.orElseThrow().packages().size(),
                        0,
                        lockfile.orElseThrow().conflicts().size(),
                        lockfilePath),
                content.orElseThrow().getBytes(StandardCharsets.UTF_8),
                lockfile.orElseThrow(),
                true));
    }

    private static boolean matches(Workspace workspace, ZoltLockfile lockfile, String content) {
        Optional<String> recorded = lockfile.workspaceResolutionInputFingerprint();
        return recorded.isPresent()
                && WorkspaceResolutionInputFingerprint.fingerprint(workspace, content)
                        .filter(recorded.orElseThrow()::equals)
                        .isPresent();
    }

    /** Absent rather than fatal for an unreadable lock, so the full resolve reports the problem. */
    private static Optional<ZoltLockfile> parse(ZoltLockfileReader reader, String content) {
        try {
            return Optional.of(reader.read(content));
        } catch (LockfileReadException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> readLockfile(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Files.readAllBytes(lockfilePath), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw LockfileReadException.actionable(
                    "Could not read zolt.lock at " + lockfilePath + " while checking whether it is current.",
                    "Check that the file exists and is readable.",
                    exception);
        }
    }
}
