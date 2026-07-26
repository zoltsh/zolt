package sh.zolt.quality.execution;

import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishSettingsReader;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckRequest;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.publish.WorkspacePublishService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.resolve.ResolveException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class QualityExecutionContextRunner {
    private final ExecutionContextQualityCheck executionContextQualityCheck;
    private final CredentialQualityCheck credentialQualityCheck;
    private final ExecutionEvidenceQualityCheck executionEvidenceQualityCheck;
    private final PublishDryRunQualityCheck publishDryRunQualityCheck;
    private final WorkspaceMemberPolicyResolver workspacePolicyResolver;

    private QualityExecutionContextRunner(
            ExecutionContextQualityCheck executionContextQualityCheck,
            CredentialQualityCheck credentialQualityCheck,
            ExecutionEvidenceQualityCheck executionEvidenceQualityCheck,
            PublishDryRunQualityCheck publishDryRunQualityCheck,
            WorkspaceMemberPolicyResolver workspacePolicyResolver) {
        this.executionContextQualityCheck = executionContextQualityCheck;
        this.credentialQualityCheck = credentialQualityCheck;
        this.executionEvidenceQualityCheck = executionEvidenceQualityCheck;
        this.publishDryRunQualityCheck = publishDryRunQualityCheck;
        this.workspacePolicyResolver = workspacePolicyResolver;
    }

    public static QualityExecutionContextRunner create(
            ZoltLockfileReader lockfileReader,
            PublishSettingsReader publishSettingsReader,
            Function<String, String> environment,
            PublishDryRunService publishDryRunService) {
        return create(
                lockfileReader,
                publishSettingsReader,
                environment,
                publishDryRunService,
                new WorkspacePublishService());
    }

    public static QualityExecutionContextRunner create(
            ZoltLockfileReader lockfileReader,
            PublishSettingsReader publishSettingsReader,
            Function<String, String> environment,
            PublishDryRunService publishDryRunService,
            WorkspacePublishService workspacePublishService) {
        return new QualityExecutionContextRunner(
                new ExecutionContextQualityCheck(lockfileReader),
                new CredentialQualityCheck(publishSettingsReader, environment),
                new ExecutionEvidenceQualityCheck(),
                new PublishDryRunQualityCheck(
                        publishDryRunService,
                        workspacePublishService),
                new WorkspaceMemberPolicyResolver());
    }

    public List<QualityCheckResult> checkProject(QualityCheckRequest request, ProjectConfig config) {
        List<QualityCheckResult> results = new ArrayList<>();
        results.addAll(executionContextQualityCheck.check(
                Optional.empty(),
                request.projectRoot(),
                request.context()));
        results.addAll(credentialQualityCheck.checkRepositoryCredentials(
                Optional.empty(),
                config,
                request.context()));
        results.addAll(credentialQualityCheck.checkPublishCredentials(
                Optional.empty(),
                request.projectRoot(),
                config,
                request.context()));
        results.addAll(credentialQualityCheck.checkResourceTokens(
                Optional.empty(),
                config,
                request.context()));
        results.addAll(executionEvidenceQualityCheck.checkTestReports(
                Optional.empty(),
                request.projectRoot(),
                request.reportsDir(),
                request.reportsDir(),
                java.nio.file.Path.of(config.build().outputRoot()),
                request.context()));
        results.addAll(executionEvidenceQualityCheck.checkCoverageReports(
                Optional.empty(),
                request.projectRoot(),
                request.coverageDir(),
                request.coverageDir(),
                java.nio.file.Path.of(config.build().outputRoot()),
                request.context()));
        results.addAll(publishDryRunQualityCheck.check(
                Optional.empty(),
                request.projectRoot(),
                request.cacheRoot(),
                request.context(),
                request.requirePublishDryRun()));
        return List.copyOf(results);
    }

    public List<QualityCheckResult> checkWorkspace(
            QualityCheckRequest request,
            Workspace workspace,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> members) {
        List<QualityCheckResult> results = new ArrayList<>();
        results.addAll(executionContextQualityCheck.check(
                Optional.empty(),
                workspace.root(),
                request.context()));
        if (request.context() != QualityCheckContext.CI) {
            return List.copyOf(results);
        }
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMember member = members.get(memberPath);
            Optional<String> memberName = Optional.of(member.path());
            ProjectConfig effectiveConfig;
            try {
                effectiveConfig =
                        workspacePolicyResolver.merge(
                                workspace,
                                member);
            } catch (ResolveException exception) {
                results.add(QualityCheckResult.failed(
                        sh.zolt.quality.QualityCheckService.EXECUTION_CONTEXT,
                        memberName,
                        "workspace-policy",
                        exception.getMessage(),
                        "Make the workspace-root and member policy values match, then rerun `zolt check --workspace --context ci`."));
                continue;
            }
            results.addAll(credentialQualityCheck.checkRepositoryCredentials(
                    memberName,
                    effectiveConfig,
                    request.context()));
            results.addAll(credentialQualityCheck.checkPublishCredentials(
                    memberName,
                    member.directory(),
                    effectiveConfig,
                    request.context()));
            results.addAll(credentialQualityCheck.checkResourceTokens(
                    memberName,
                    effectiveConfig,
                    request.context()));
        }
        // The publish dry-run runs once as a family preflight, not per member.
        results.addAll(publishDryRunQualityCheck.checkWorkspaceFamily(
                workspace.root(),
                request.cacheRoot(),
                request.workspaceSelection(),
                request.context(),
                request.requirePublishDryRun()));
        for (String memberPath : selection.selectedMembers()) {
            WorkspaceMember member = members.get(memberPath);
            Optional<String> memberName = Optional.of(member.path());
            results.addAll(executionEvidenceQualityCheck.checkTestReports(
                    memberName,
                    member.directory(),
                    request.reportsDir() == null ? null : request.reportsDir().resolve(member.path()),
                    request.reportsDir(),
                    java.nio.file.Path.of(member.config().build().outputRoot()),
                    request.context()));
            results.addAll(executionEvidenceQualityCheck.checkCoverageReports(
                    memberName,
                    member.directory(),
                    request.coverageDir(),
                    request.coverageDir(),
                    java.nio.file.Path.of(member.config().build().outputRoot()),
                    request.context()));
        }
        return List.copyOf(results);
    }
}
