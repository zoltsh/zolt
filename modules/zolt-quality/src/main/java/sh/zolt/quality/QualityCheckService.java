package sh.zolt.quality;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quality.execution.QualityExecutionContextRunner;
import sh.zolt.quality.packaging.PackageQualityCheck;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.publish.WorkspaceMemberDirectory;
import sh.zolt.workspace.service.*;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class QualityCheckService {
    public static final String COMMAND_SURFACE = QualityCheckCatalog.COMMAND_SURFACE;
    public static final String CACHE_INTEGRITY = QualityCheckCatalog.CACHE_INTEGRITY;
    public static final String LOCKFILE = QualityCheckCatalog.LOCKFILE;
    public static final String PROJECT_MODEL = QualityCheckCatalog.PROJECT_MODEL;
    public static final String DEPENDENCY_METADATA = QualityCheckCatalog.DEPENDENCY_METADATA;
    public static final String DEPENDENCY_POLICY = QualityCheckCatalog.DEPENDENCY_POLICY;
    public static final String LICENSE_POLICY = QualityCheckCatalog.LICENSE_POLICY;
    public static final String PACKAGE_METADATA = QualityCheckCatalog.PACKAGE_METADATA;
    public static final String PACKAGE_CONTENTS = QualityCheckCatalog.PACKAGE_CONTENTS;
    public static final String MANIFEST_METADATA = QualityCheckCatalog.MANIFEST_METADATA;
    public static final String GENERATED_SOURCES = QualityCheckCatalog.GENERATED_SOURCES;
    public static final String EXECUTION_CONTEXT = QualityCheckCatalog.EXECUTION_CONTEXT;

    private final ManifestProjectConfigLoader manifestLoader;
    private final ManifestWorkspaceLoader workspaceLoader;
    private final WorkspaceMemberDirectory memberDirectory = new WorkspaceMemberDirectory();
    private final WorkspaceMemberSelector workspaceMemberSelector;
    private final WorkspaceQualityChecks workspaceChecks;
    private final GeneratedSourceQualityCheck generatedSourceQualityCheck;
    private final LockfileQualityCheck lockfileQualityCheck;
    private final QualityExecutionContextRunner executionContextRunner;
    private final ProjectModelQualityCheck projectModelQualityCheck;
    private final PackageQualityCheck packageQualityCheck;
    private final DependencyQualityCheck dependencyQualityCheck;
    private final LicensePolicyQualityCheck licensePolicyQualityCheck;

    public QualityCheckService() {
        this(QualityCheckDependencies.create(System::getenv));
    }

    QualityCheckService(Function<String, String> environment) {
        this(QualityCheckDependencies.create(environment));
    }

    QualityCheckService(QualityCheckDependencies dependencies) {
        this(
                new ManifestProjectConfigLoader(),
                new ManifestWorkspaceLoader(),
                new WorkspaceMemberSelector(),
                dependencies);
    }

    QualityCheckService(
            ManifestProjectConfigLoader manifestLoader,
            ManifestWorkspaceLoader workspaceLoader,
            WorkspaceMemberSelector workspaceMemberSelector,
            QualityCheckDependencies dependencies) {
        this.manifestLoader = manifestLoader;
        this.workspaceLoader = workspaceLoader;
        this.workspaceMemberSelector = workspaceMemberSelector;
        this.workspaceChecks = new WorkspaceQualityChecks(dependencies);
        this.generatedSourceQualityCheck = dependencies.generatedSourceQualityCheck();
        this.lockfileQualityCheck = dependencies.lockfileQualityCheck();
        this.executionContextRunner = dependencies.executionContextRunner();
        this.projectModelQualityCheck = new ProjectModelQualityCheck();
        this.packageQualityCheck = dependencies.packageQualityCheck();
        this.dependencyQualityCheck = dependencies.dependencyQualityCheck();
        this.licensePolicyQualityCheck = dependencies.licensePolicyQualityCheck();
    }

    public QualityCheckReport check(QualityCheckRequest request) {
        List<String> requestedChecks = QualityCheckCatalog.requestedChecks(request);
        Path root = request.projectRoot();

        if (request.workspace()) {
            try {
                Optional<Workspace> maybeWorkspace = workspaceLoader.discover(root);
                if (maybeWorkspace.isEmpty()) {
                    return new QualityCheckReport(root, true, QualityCheckCatalog.unavailableResults(
                            requestedChecks,
                            "workspace config",
                            "No Zolt workspace was found for `zolt check --workspace`.",
                            "Run from a workspace root or remove --workspace for a single-project check."));
                }
                Workspace workspace = maybeWorkspace.orElseThrow();
                WorkspaceSelection selection = workspaceMemberSelector.select(workspace, request.workspaceSelection());
                return new QualityCheckReport(
                        root,
                        true,
                        workspaceChecks.run(request, requestedChecks, workspace, selection, Optional.empty()));
            } catch (WorkspaceConfigException exception) {
                return new QualityCheckReport(root, true, QualityCheckCatalog.unavailableResults(
                        requestedChecks,
                        "workspace config",
                        exception.getMessage(),
                        "Fix workspace config or run `zolt check` for a single project."));
            }
        }

        // A member directory without --workspace is still a member. Its config was composed against the
        // workspace root and its dependency facts live in the workspace root's lock, so running the
        // project path here would check a workspace-member config against a member-local lock that does
        // not exist — a split-brain answer, and in practice a "zolt.lock is missing" failure over a lock
        // that is present. The member's checks are the workspace's checks with exactly this member
        // selected; membership is settled from config alone, before any lock is read.
        Optional<WorkspaceMemberDirectory.Membership> membership = membership(root);
        if (membership.isPresent()) {
            Workspace workspace = membership.orElseThrow().workspace();
            String memberPath = membership.orElseThrow().member().path();
            WorkspaceSelection selection = workspaceMemberSelector.select(
                    workspace, WorkspaceSelectionRequest.exact(List.of(memberPath)));
            return new QualityCheckReport(
                    root,
                    false,
                    workspaceChecks.run(
                            request, requestedChecks, workspace, selection, Optional.of(memberPath)));
        }

        try {
            ProjectConfig config = manifestLoader.loadProject(root);
            return new QualityCheckReport(root, false, runProjectChecks(request, requestedChecks, config));
        } catch (ZoltConfigException exception) {
            return new QualityCheckReport(root, false, QualityCheckCatalog.unavailableResults(
                    requestedChecks,
                    "zolt.toml",
                    exception.getMessage(),
                    "Fix zolt.toml, then run `zolt check` again."));
        }
    }

    public static Set<String> supportedChecks() {
        return QualityCheckCatalog.supportedChecks();
    }

    /**
     * Whether this directory is a declared workspace member — the routing question, asked from config
     * alone and before any lock is read.
     *
     * <p>A manifest too broken to discover a workspace through is NOT reported here. Membership is only
     * a choice of path; the standalone path's own loader diagnoses the same file precisely (naming
     * {@code zolt.toml} and the exact invalid symbol), and answering with a vaguer "workspace config"
     * failure would replace a good diagnosis with a worse one for a project that has no workspace.
     */
    private Optional<WorkspaceMemberDirectory.Membership> membership(Path projectRoot) {
        try {
            return memberDirectory.membershipAt(projectRoot);
        } catch (WorkspaceConfigException exception) {
            return Optional.empty();
        }
    }

    private static QualityCheckResult commandSurfaceProjectResult(ProjectConfig config) {
        return QualityCheckResult.passed(
                COMMAND_SURFACE,
                Optional.empty(),
                config.project().name(),
                "zolt check uses typed Zolt project data; no Maven, Gradle, or shell hooks are run.");
    }

    private List<QualityCheckResult> runProjectChecks(
            QualityCheckRequest request,
            List<String> requestedChecks,
            ProjectConfig config) {
        List<QualityCheckResult> results = new ArrayList<>();
        Path lockfile = ProjectLockfile.in(request.projectRoot());
        for (String requestedCheck : requestedChecks) {
            switch (requestedCheck) {
                case COMMAND_SURFACE -> results.add(commandSurfaceProjectResult(config));
                case CACHE_INTEGRITY -> results.add(lockfileQualityCheck.checkProjectCacheIntegrity(request));
                case EXECUTION_CONTEXT -> results.addAll(executionContextRunner.checkProject(request, config));
                case LOCKFILE -> results.add(lockfileQualityCheck.checkProjectLockfile(request, config));
                case PROJECT_MODEL -> results.addAll(projectModelQualityCheck.check(
                        Optional.empty(),
                        request.projectRoot(),
                        config));
                case DEPENDENCY_METADATA -> results.addAll(dependencyQualityCheck.checkProjectMetadata(
                        Optional.empty(),
                        request.projectRoot(),
                        config,
                        false));
                case DEPENDENCY_POLICY -> results.addAll(dependencyQualityCheck.checkPolicy(
                        Optional.empty(),
                        request.projectRoot(),
                        config,
                        lockfile,
                        false));
                case LICENSE_POLICY -> results.addAll(licensePolicyQualityCheck.check(
                        Optional.empty(),
                        request.projectRoot(),
                        config,
                        lockfile,
                        false,
                        request.cacheRoot()));
                case PACKAGE_METADATA -> results.add(packageQualityCheck.checkMetadata(
                        Optional.empty(),
                        request.projectRoot(),
                        config));
                case PACKAGE_CONTENTS -> results.addAll(packageQualityCheck.checkContents(
                        Optional.empty(),
                        request.projectRoot(),
                        config,
                        lockfile,
                        request.cacheRoot(),
                        request.requirePackage()));
                case MANIFEST_METADATA -> results.add(packageQualityCheck.checkManifestMetadata(
                        Optional.empty(),
                        config));
                case GENERATED_SOURCES -> results.addAll(generatedSourceQualityCheck.check(
                        Optional.empty(),
                        request.projectRoot(),
                        config,
                        request.context() == QualityCheckContext.CI && request.requireOfflineReady()));
                default -> results.add(QualityCheckCatalog.unsupportedOrSkipped(requestedCheck));
            }
        }
        return List.copyOf(results);
    }

}
