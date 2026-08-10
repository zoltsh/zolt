package sh.zolt.update;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import java.util.Objects;
import java.util.Optional;

/**
 * One project unit to report on: a display label, its parsed configuration, and its lockfile when
 * present (used to show effective versions of platform-managed dependencies). A single project is
 * one scope; a workspace is one scope per member (plus the root when it declares surfaces).
 */
public record OutdatedScope(
        String label,
        String manifestPath,
        String lockfilePath,
        ProjectConfig config,
        Optional<ZoltLockfile> lockfile) {
    public OutdatedScope {
        label = Objects.requireNonNull(label, "label");
        manifestPath = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        config = Objects.requireNonNull(config, "config");
        lockfile = lockfile == null ? Optional.empty() : lockfile;
    }

    public OutdatedScope(String label, ProjectConfig config, Optional<ZoltLockfile> lockfile) {
        this(label, "zolt.toml", "zolt.lock", config, lockfile);
    }

    public static OutdatedScope of(String label, ProjectConfig config) {
        return new OutdatedScope(label, config, Optional.empty());
    }
}
