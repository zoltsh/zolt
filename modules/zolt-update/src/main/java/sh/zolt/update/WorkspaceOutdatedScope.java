package sh.zolt.update;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.Objects;
import java.util.Optional;

/** One workspace-root policy manifest whose dependency surfaces are reported independently. */
public record WorkspaceOutdatedScope(
        String label,
        String manifestPath,
        String lockfilePath,
        WorkspaceConfig config,
        Optional<ZoltLockfile> lockfile) implements UpdateReportScope {
    public WorkspaceOutdatedScope {
        label = Objects.requireNonNull(label, "label");
        manifestPath = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        config = Objects.requireNonNull(config, "config");
        lockfile = lockfile == null ? Optional.empty() : lockfile;
    }
}
