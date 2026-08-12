package sh.zolt.update;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.RepositoryConfiguration;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One workspace-root policy manifest whose dependency surfaces are reported independently. */
public record WorkspaceOutdatedScope(
        String label,
        String manifestPath,
        String lockfilePath,
        WorkspaceConfig config,
        Optional<ZoltLockfile> lockfile,
        List<RepositoryConfiguration> repositoryConfigurations,
        Map<UpdateTargetId, String> targetBlockers) implements UpdateReportScope {
    public WorkspaceOutdatedScope {
        label = Objects.requireNonNull(label, "label");
        manifestPath = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        config = Objects.requireNonNull(config, "config");
        lockfile = lockfile == null ? Optional.empty() : lockfile;
        repositoryConfigurations = repositoryConfigurations == null || repositoryConfigurations.isEmpty()
                ? List.of(config)
                : List.copyOf(repositoryConfigurations);
        targetBlockers = targetBlockers == null ? Map.of() : Map.copyOf(targetBlockers);
    }

    public WorkspaceOutdatedScope(
            String label,
            String manifestPath,
            String lockfilePath,
            WorkspaceConfig config,
            Optional<ZoltLockfile> lockfile) {
        this(label, manifestPath, lockfilePath, config, lockfile, List.of(config), Map.of());
    }
}
