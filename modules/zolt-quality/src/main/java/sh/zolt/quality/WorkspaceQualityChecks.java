package sh.zolt.quality;

import static sh.zolt.quality.QualityCheckCatalog.CACHE_INTEGRITY;
import static sh.zolt.quality.QualityCheckCatalog.COMMAND_SURFACE;
import static sh.zolt.quality.QualityCheckCatalog.DEPENDENCY_METADATA;
import static sh.zolt.quality.QualityCheckCatalog.DEPENDENCY_POLICY;
import static sh.zolt.quality.QualityCheckCatalog.EXECUTION_CONTEXT;
import static sh.zolt.quality.QualityCheckCatalog.GENERATED_SOURCES;
import static sh.zolt.quality.QualityCheckCatalog.LICENSE_POLICY;
import static sh.zolt.quality.QualityCheckCatalog.LOCKFILE;
import static sh.zolt.quality.QualityCheckCatalog.MANIFEST_METADATA;
import static sh.zolt.quality.QualityCheckCatalog.PACKAGE_CONTENTS;
import static sh.zolt.quality.QualityCheckCatalog.PACKAGE_METADATA;
import static sh.zolt.quality.QualityCheckCatalog.PROJECT_MODEL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sh.zolt.quality.execution.QualityExecutionContextRunner;
import sh.zolt.quality.packaging.PackageQualityCheck;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;

/**
 * Runs the workspace-projected quality checks for a selection of members.
 *
 * <p>This is the one path every member's checks take, whether the user asked for the workspace or
 * simply ran {@code zolt check} inside a member directory. Splitting it out of
 * {@link QualityCheckService} leaves that class holding only the routing decision — standalone
 * project, workspace, or one member of a workspace — which is the decision the design's audit rule is
 * actually about.
 */
final class WorkspaceQualityChecks {
    private final GeneratedSourceQualityCheck generatedSourceQualityCheck;
    private final LockfileQualityCheck lockfileQualityCheck;
    private final QualityExecutionContextRunner executionContextRunner;
    private final ProjectModelQualityCheck projectModelQualityCheck = new ProjectModelQualityCheck();
    private final PackageQualityCheck packageQualityCheck;
    private final DependencyQualityCheck dependencyQualityCheck;
    private final LicensePolicyQualityCheck licensePolicyQualityCheck;
    private final WorkspaceQualityProjectionService workspaceQualityProjectionService;

    WorkspaceQualityChecks(QualityCheckDependencies dependencies) {
        this.generatedSourceQualityCheck = dependencies.generatedSourceQualityCheck();
        this.lockfileQualityCheck = dependencies.lockfileQualityCheck();
        this.executionContextRunner = dependencies.executionContextRunner();
        this.packageQualityCheck = dependencies.packageQualityCheck();
        this.dependencyQualityCheck = dependencies.dependencyQualityCheck();
        this.licensePolicyQualityCheck = dependencies.licensePolicyQualityCheck();
        this.workspaceQualityProjectionService = dependencies.workspaceQualityProjectionService();
    }

    /**
     * @param memberScope the one member this run is scoped to, when the command was started in a member
     *     directory rather than asked for the workspace. Everything the workspace path does per member
     *     is exactly what that member needs; the difference is only that workspace-wide findings — the
     *     root manifest's own stale exclusions — are not this member's to report.
     */
    List<QualityCheckResult> run(
            QualityCheckRequest request,
            List<String> requestedChecks,
            Workspace workspace,
            WorkspaceSelection selection,
            Optional<String> memberScope) {
        List<QualityCheckResult> results = new ArrayList<>();
        Map<String, WorkspaceMember> members = membersByPath(workspace);
        WorkspaceQualityProjection qualityProjection = null;
        WorkspaceQualityProjectionException projectionFailure = null;
        if (requestedChecks.stream().anyMatch(WorkspaceQualityChecks::graphDependentCheck)) {
            try {
                qualityProjection = workspaceQualityProjectionService.project(
                        workspace,
                        selection,
                        members,
                        requestedChecks.contains(PACKAGE_CONTENTS),
                        request.cacheRoot());
            } catch (WorkspaceQualityProjectionException exception) {
                projectionFailure = exception;
            }
        }
        for (String requestedCheck : requestedChecks) {
            switch (requestedCheck) {
                case COMMAND_SURFACE ->
                        results.add(commandSurfaceWorkspaceResult(workspace, selection, memberScope));
                case CACHE_INTEGRITY -> results.add(lockfileQualityCheck.checkWorkspaceCacheIntegrity(request, workspace));
                case EXECUTION_CONTEXT -> results.addAll(executionContextRunner.checkWorkspace(
                        request,
                        workspace,
                        selection,
                        members));
                case LOCKFILE -> results.add(lockfileQualityCheck.checkWorkspaceLockfile(request, workspace));
                case PROJECT_MODEL -> {
                    if (memberScope.isEmpty()) {
                        WorkspaceStaleExclusionCheck.check(workspace).ifPresent(results::add);
                    }
                    for (String memberPath : selection.includedMembers()) {
                        WorkspaceMember member = members.get(memberPath);
                        results.addAll(projectModelQualityCheck.check(
                                Optional.of(member.path()),
                                member.directory(),
                                member.config()));
                    }
                }
                case DEPENDENCY_METADATA -> {
                    if (projectionFailure != null) {
                        results.add(graphProjectionFailure(requestedCheck, projectionFailure));
                    } else {
                        results.addAll(dependencyQualityCheck.checkWorkspaceMetadata(
                                workspace,
                                selection,
                                qualityProjection));
                    }
                }
                case DEPENDENCY_POLICY -> {
                    if (projectionFailure != null) {
                        results.add(graphProjectionFailure(requestedCheck, projectionFailure));
                    } else {
                        for (String memberPath : selection.includedMembers()) {
                            WorkspaceMemberQualityView view = qualityProjection.member(memberPath);
                            results.addAll(dependencyQualityCheck.checkProjectedPolicy(
                                    Optional.of(memberPath),
                                    view.member().directory(),
                                    view.effectiveConfig(),
                                    view.policyLock()));
                        }
                    }
                }
                case LICENSE_POLICY -> {
                    if (projectionFailure != null) {
                        results.add(graphProjectionFailure(requestedCheck, projectionFailure));
                    } else {
                        for (String memberPath : selection.includedMembers()) {
                            WorkspaceMemberQualityView view = qualityProjection.member(memberPath);
                            results.addAll(licensePolicyQualityCheck.checkProjected(
                                    Optional.of(memberPath),
                                    view.effectiveConfig(),
                                    view.sbomLock(),
                                    request.cacheRoot()));
                        }
                    }
                }
                case PACKAGE_METADATA -> {
                    for (String memberPath : selection.includedMembers()) {
                        WorkspaceMember member = members.get(memberPath);
                        results.add(packageQualityCheck.checkMetadata(
                                Optional.of(member.path()),
                                member.directory(),
                                member.config()));
                    }
                }
                case PACKAGE_CONTENTS -> {
                    if (projectionFailure != null) {
                        results.add(graphProjectionFailure(requestedCheck, projectionFailure));
                    } else {
                        for (String memberPath : selection.includedMembers()) {
                            WorkspaceMemberQualityView view = qualityProjection.member(memberPath);
                            results.addAll(packageQualityCheck.checkContents(
                                    Optional.of(memberPath),
                                    view.effectiveConfig(),
                                    view.packagePlan().orElseThrow(),
                                    request.requirePackage()));
                        }
                    }
                }
                case MANIFEST_METADATA -> {
                    for (String memberPath : selection.includedMembers()) {
                        WorkspaceMember member = members.get(memberPath);
                        results.add(packageQualityCheck.checkManifestMetadata(
                                Optional.of(member.path()),
                                member.config()));
                    }
                }
                case GENERATED_SOURCES -> {
                    for (String memberPath : selection.includedMembers()) {
                        WorkspaceMember member = members.get(memberPath);
                        results.addAll(generatedSourceQualityCheck.check(
                                Optional.of(member.path()),
                                member.directory(),
                                member.config(),
                                request.context() == QualityCheckContext.CI && request.requireOfflineReady()));
                    }
                }
                default -> results.add(QualityCheckCatalog.unsupportedOrSkipped(requestedCheck));
            }
        }
        return List.copyOf(results);
    }

    private static QualityCheckResult commandSurfaceWorkspaceResult(
            Workspace workspace,
            WorkspaceSelection selection,
            Optional<String> memberScope) {
        if (memberScope.isPresent()) {
            return QualityCheckResult.passed(
                    COMMAND_SURFACE,
                    memberScope,
                    workspace.members().stream()
                            .filter(member -> member.path().equals(memberScope.orElseThrow()))
                            .map(member -> member.config().project().name())
                            .findFirst()
                            .orElseGet(memberScope::orElseThrow),
                    "zolt check projected this workspace member from the workspace lock using typed Zolt "
                            + "workspace data; no Maven, Gradle, or shell hooks are run.");
        }
        return QualityCheckResult.passed(
                COMMAND_SURFACE,
                Optional.empty(),
                workspace.root().getFileName().toString(),
                "zolt check selected "
                        + selection.includedMembers().size()
                        + " workspace members using typed Zolt workspace data; no Maven, Gradle, or shell hooks are run.");
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return Collections.unmodifiableMap(members);
    }

    private static boolean graphDependentCheck(String check) {
        return Set.of(DEPENDENCY_METADATA, DEPENDENCY_POLICY, LICENSE_POLICY, PACKAGE_CONTENTS).contains(check);
    }

    private static QualityCheckResult graphProjectionFailure(
            String check,
            WorkspaceQualityProjectionException failure) {
        return QualityCheckResult.failed(
                check,
                Optional.empty(),
                "zolt.lock",
                failure.getMessage(),
                failure.nextStep());
    }
}
