package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.ContentAddressedLockCapability;
import sh.zolt.lockfile.LockfileFreshnessSummary;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.LockfileSidecars;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/** Persists a workspace lock and returns the exact bytes committed by that transaction. */
final class WorkspaceResolveLockfilePersistence {
    private final ZoltLockfileReader reader = new ZoltLockfileReader();
    private final ZoltLockfileWriter writer;

    WorkspaceResolveLockfilePersistence(ZoltLockfileWriter writer) {
        this.writer = writer;
    }

    ResolveOptions prepare(Path path, ResolveOptions options, boolean locked) {
        if (locked) {
            ContentAddressedLockCapability.requireArtifactCachePaths(
                    reader.read(path),
                    "zolt resolve --workspace");
        }
        if (!options.includeCoverageTooling()
                && existingLockfileHasCoverageTooling(path)) {
            return options.withCoverageTooling();
        }
        return options;
    }

    CommittedLockfile persist(
            Path path,
            ZoltLockfile candidate,
            boolean locked) {
        String committed = locked
                ? verifyLocked(path, candidate)
                : update(path, existing ->
                        LockfileSidecars.withJavaToolchainBlocksFromExisting(
                                writer.write(candidate),
                                existing));
        byte[] bytes = committed.getBytes(StandardCharsets.UTF_8);
        return new CommittedLockfile(bytes, reader.read(committed));
    }

    private boolean existingLockfileHasCoverageTooling(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            return reader.read(path).packages().stream()
                    .anyMatch(lockPackage ->
                            lockPackage.scope()
                                    == DependencyScope.TOOL_COVERAGE);
        } catch (LockfileReadException exception) {
            return false;
        }
    }

    private String verifyLocked(Path path, ZoltLockfile candidate) {
        String existing;
        try {
            existing = Files.readString(path);
        } catch (IOException exception) {
            throw new ResolveException(
                    "Could not read zolt.lock at " + path
                            + " for locked workspace resolve. Check that the file exists and is readable.",
                    exception);
        }
        String expected = writer.write(candidate);
        if (!LockfileSidecars.canonicalDependencyLockfile(existing)
                .equals(LockfileSidecars.canonicalDependencyLockfile(
                        expected))) {
            throw new ResolveException(
                    "Workspace zolt.lock is out of date."
                            + changedInputs(existing, candidate)
                            + " Run `zolt resolve --workspace` to refresh it, then retry `zolt resolve --workspace --locked`.");
        }
        return existing;
    }

    private String update(Path path, UnaryOperator<String> mutation) {
        try {
            return AtomicLockfileWriter.updateAndReturn(path, mutation);
        } catch (IOException exception) {
            throw new ResolveException(
                    "Could not write zolt.lock at " + path
                            + ". Check that the directory exists and is writable.",
                    exception);
        }
    }

    private String changedInputs(String existing, ZoltLockfile candidate) {
        try {
            return LockfileFreshnessSummary.changedInputs(
                    reader.read(existing),
                    candidate);
        } catch (LockfileReadException exception) {
            return "";
        }
    }

    record CommittedLockfile(byte[] bytes, ZoltLockfile lockfile) {
        CommittedLockfile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
