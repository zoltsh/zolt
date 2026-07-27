package sh.zolt.workspace.publish;

import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishDryRunPlan;
import sh.zolt.publish.PublishSettings;
import java.nio.file.Path;

/**
 * One workspace member's publish dry-run plan, planned from the workspace-aggregated root lock rather
 * than a member-level {@code zolt.lock} (members never have one).
 *
 * <p>Carries the policy-merged {@link ProjectConfig} and the member's {@link PublishSettings} alongside
 * the plan so a caller can evaluate Central readiness against the same merged inputs the plan was built
 * from, instead of re-reading the member's raw {@code zolt.toml}.
 */
public record WorkspaceMemberDryRun(
        Path memberDirectory,
        String memberPath,
        String coordinate,
        ProjectConfig config,
        PublishSettings publish,
        PublishDryRunPlan plan) {
}
