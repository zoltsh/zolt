package sh.zolt.update;

import sh.zolt.lockfile.ZoltLockfile;
import java.util.Optional;

/** Common report identity for project manifests and workspace-root policy manifests. */
public sealed interface UpdateReportScope permits OutdatedScope, WorkspaceOutdatedScope {
    String label();

    String manifestPath();

    String lockfilePath();

    Optional<ZoltLockfile> lockfile();
}
