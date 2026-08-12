package sh.zolt.update;

import sh.zolt.project.ProjectConfig;
import java.util.Map;
import java.util.Objects;

/** Raw mutation identity plus the effective repository configuration used for policy discovery. */
public record UpdatePlanningScope(
        ProjectConfig mutationConfig,
        ProjectConfig discoveryConfig,
        String manifestPath,
        String lockfilePath,
        Map<UpdateTargetKey, String> targetBlockers) {
    public UpdatePlanningScope {
        mutationConfig = Objects.requireNonNull(mutationConfig, "mutationConfig");
        discoveryConfig = discoveryConfig == null ? mutationConfig : discoveryConfig;
        manifestPath = UpdateTargetKey.requirePath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetKey.requirePath(lockfilePath, "lockfile path");
        targetBlockers = targetBlockers == null ? Map.of() : Map.copyOf(targetBlockers);
    }

    public static UpdatePlanningScope standalone(ProjectConfig config) {
        return new UpdatePlanningScope(config, config, "zolt.toml", "zolt.lock", Map.of());
    }
}
