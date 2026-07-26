package sh.zolt.quality.execution;

import static sh.zolt.quality.QualityCheckService.EXECUTION_CONTEXT;

import sh.zolt.publish.PublishDryRunPlan;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.publish.WorkspacePublishReport;
import sh.zolt.workspace.publish.WorkspacePublishService;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PublishDryRunQualityCheck {
    private final PublishDryRunService publishDryRunService;
    private final WorkspacePublishService workspacePublishService;

    PublishDryRunQualityCheck(PublishDryRunService publishDryRunService) {
        this(publishDryRunService, new WorkspacePublishService());
    }

    public PublishDryRunQualityCheck(
            PublishDryRunService publishDryRunService, WorkspacePublishService workspacePublishService) {
        this.publishDryRunService = publishDryRunService;
        this.workspacePublishService = workspacePublishService;
    }

    /**
     * Runs the Phase-1 family preflight once for the whole workspace: a {@code zolt publish
     * --workspace --dry-run} that aggregates every member's blockers into one CI gate.
     */
    List<QualityCheckResult> checkWorkspaceFamily(
            Path workspaceRoot,
            Path cacheRoot,
            WorkspaceSelectionRequest selection,
            QualityCheckContext context,
            boolean requirePublishDryRun) {
        if (context != QualityCheckContext.CI || !requirePublishDryRun) {
            return List.of();
        }
        try {
            WorkspacePublishReport report = workspacePublishService.publish(
                    workspaceRoot,
                    cacheRoot,
                    selection,
                    new WorkspacePublishService.Options(true, false, false, Optional.empty()));
            if (!report.ok()) {
                List<QualityCheckResult> results = new ArrayList<>();
                for (String blocker : report.blockers()) {
                    results.add(QualityCheckResult.failed(
                            EXECUTION_CONTEXT,
                            Optional.empty(),
                            "publish-dry-run",
                            "CI workspace publish dry-run blocker: " + blocker,
                            "Run `zolt publish --workspace --dry-run` and resolve the reported blockers before release CI."));
                }
                return List.copyOf(results);
            }
            return List.of(QualityCheckResult.passed(
                    EXECUTION_CONTEXT,
                    Optional.empty(),
                    "publish-dry-run",
                    "CI workspace publish dry-run preflight is ready for "
                            + report.members().size()
                            + " family member(s)."));
        } catch (PublishException | WorkspaceConfigException | sh.zolt.resolve.ResolveException exception) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    Optional.empty(),
                    "publish-dry-run",
                    "CI workspace publish dry-run preflight failed: " + exception.getMessage(),
                    "Configure [publish] on family members, run `zolt package --workspace`, then retry `zolt check --context ci --require-publish-dry-run --workspace`."));
        }
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path projectRoot,
            QualityCheckContext context,
            boolean requirePublishDryRun) {
        return check(
                member,
                projectRoot,
                LocalArtifactCache.defaultRoot(),
                context,
                requirePublishDryRun);
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path projectRoot,
            Path cacheRoot,
            QualityCheckContext context,
            boolean requirePublishDryRun) {
        if (context != QualityCheckContext.CI || !requirePublishDryRun) {
            return List.of();
        }
        if (member.isPresent()) {
            // Workspace members are covered by the one-shot family preflight (checkWorkspaceFamily).
            return List.of();
        }
        try {
            PublishDryRunPlan plan = publishDryRunService.plan(
                    projectRoot,
                    true,
                    Optional.empty(),
                    cacheRoot);
            if (!plan.ok()) {
                List<QualityCheckResult> results = new ArrayList<>();
                for (String blocker : plan.blockers()) {
                    results.add(QualityCheckResult.failed(
                            EXECUTION_CONTEXT,
                            member,
                            "publish-dry-run",
                            "CI publish dry-run blocker: " + blocker,
                            "Run `zolt publish --dry-run` and resolve the reported blocker before release CI."));
                }
                return List.copyOf(results);
            }
            int artifactCount = 1 + plan.supplementalArtifacts().size();
            return List.of(QualityCheckResult.passed(
                    EXECUTION_CONTEXT,
                    member,
                    "publish-dry-run",
                    "CI publish dry-run preflight is ready for "
                            + plan.coordinate()
                            + " to repository `"
                            + plan.repositoryId()
                            + "` with "
                            + artifactCount
                            + " "
                            + (artifactCount == 1 ? "artifact" : "artifacts")
                            + " and generated POM metadata."));
        } catch (PublishException exception) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "publish-dry-run",
                    "CI publish dry-run preflight failed: " + exception.getMessage(),
                    "Configure [publish], run `zolt package`, then retry `zolt check --context ci --require-publish-dry-run`."));
        }
    }
}
