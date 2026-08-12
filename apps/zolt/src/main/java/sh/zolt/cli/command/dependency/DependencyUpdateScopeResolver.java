package sh.zolt.cli.command.dependency;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.update.OutdatedScope;
import sh.zolt.update.OutdatedScopes;
import sh.zolt.update.UpdateReportScope;
import sh.zolt.update.WorkspaceOutdatedScope;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Resolves report scopes while preserving v1 selection and providing authoritative v2 paths. */
final class DependencyUpdateScopeResolver {
    private final OutdatedScopes scopes;
    private final WorkspaceDiscoveryService workspaceDiscovery;

    DependencyUpdateScopeResolver() {
        this(new OutdatedScopes(), new WorkspaceDiscoveryService());
    }

    DependencyUpdateScopeResolver(
            OutdatedScopes scopes,
            WorkspaceDiscoveryService workspaceDiscovery) {
        this.scopes = scopes;
        this.workspaceDiscovery = workspaceDiscovery;
    }

    List<? extends UpdateReportScope> reportScopes(Path start, int schemaVersion) {
        return schemaVersion == 2 ? automationScopes(start) : legacyScopes(start);
    }

    List<CatalogUpdateScope> catalogScopes(Path start, Path confirmedMutationRoot) {
        Path mutationRoot = confirmedMutationRoot.toAbsolutePath().normalize();
        Optional<Workspace> discovered = discoverCatalogWorkspace(start, mutationRoot);
        if (discovered.isEmpty()) {
            Path project = start.toAbsolutePath().normalize();
            if (!project.equals(mutationRoot)) {
                throw changedScope();
            }
            OutdatedScope scope = scopes.fromDirectory(labelFor(project), project);
            return List.of(new ResolvedUpdateScope(
                    mutationRoot,
                    project,
                    scope.label(),
                    "zolt.toml",
                    "zolt.lock",
                    scope.config(),
                    scope.lockfile()));
        }
        Workspace workspace = discovered.orElseThrow();
        if (!workspace.root().toAbsolutePath().normalize().equals(mutationRoot)) {
            throw changedScope();
        }
        Optional<ZoltLockfile> lockfile = rootLockfile(workspace);
        WorkspaceUpdateContext context = WorkspaceUpdateContext.from(workspace);
        List<CatalogUpdateScope> resolved = new ArrayList<>();
        resolvedRootScope(workspace, lockfile, context).ifPresent(resolved::add);
        workspace.members().stream()
                .map(member -> resolvedMemberScope(workspace, member, lockfile, context))
                .forEach(resolved::add);
        return List.copyOf(resolved);
    }

    private List<OutdatedScope> legacyScopes(Path start) {
        Optional<Workspace> workspace = workspaceDiscovery.discover(start);
        if (workspace.isPresent() && sameDirectory(start, workspace.orElseThrow().root())) {
            Optional<ZoltLockfile> lockfile = scopes.readLockfile(workspace.orElseThrow().root().resolve("zolt.lock"));
            return workspace.orElseThrow().members().stream()
                    .map(member -> new OutdatedScope(member.path(), member.config(), lockfile))
                    .toList();
        }
        return List.of(scopes.fromDirectory(labelFor(start), start));
    }

    private List<UpdateReportScope> automationScopes(Path start) {
        Optional<Workspace> discovered = discoverAutomationWorkspace(start);
        if (discovered.isEmpty()) {
            return List.of(scopes.fromDirectory(labelFor(start), start));
        }
        Workspace workspace = discovered.orElseThrow();
        if (sameDirectory(start, workspace.root())) {
            return workspaceScopes(workspace);
        }
        WorkspaceMember member = workspace.members().stream()
                .filter(candidate -> sameDirectory(start, candidate.directory()))
                .findFirst()
                .orElseThrow(() -> new ZoltConfigException(
                        "Outdated schema v2 requires a standalone project, workspace root, or declared workspace member."));
        Optional<ZoltLockfile> lockfile = rootLockfile(workspace);
        WorkspaceUpdateContext context = WorkspaceUpdateContext.from(workspace);
        List<UpdateReportScope> reportScopes = new ArrayList<>();
        rootScope(workspace, lockfile, context).ifPresent(reportScopes::add);
        reportScopes.add(memberScope(workspace, member, lockfile, context));
        return List.copyOf(reportScopes);
    }

    private List<UpdateReportScope> workspaceScopes(Workspace workspace) {
        Optional<ZoltLockfile> lockfile = rootLockfile(workspace);
        WorkspaceUpdateContext context = WorkspaceUpdateContext.from(workspace);
        List<UpdateReportScope> reportScopes = new ArrayList<>();
        rootScope(workspace, lockfile, context).ifPresent(reportScopes::add);
        for (WorkspaceMember member : workspace.members()) {
            reportScopes.add(memberScope(workspace, member, lockfile, context));
        }
        return List.copyOf(reportScopes);
    }

    private OutdatedScope memberScope(
            Workspace workspace,
            WorkspaceMember member,
            Optional<ZoltLockfile> lockfile,
            WorkspaceUpdateContext context) {
        String manifestPath = CanonicalUpdatePath.relative(
                workspace.root(), member.directory().resolve("zolt.toml"));
        return new OutdatedScope(
                member.path(), manifestPath, "zolt.lock", member.config(), lockfile, context.targetBlockers());
    }

    private ResolvedUpdateScope resolvedMemberScope(
            Workspace workspace,
            WorkspaceMember member,
            Optional<ZoltLockfile> lockfile,
            WorkspaceUpdateContext context) {
        String manifestPath = CanonicalUpdatePath.relative(
                workspace.root(), member.directory().resolve("zolt.toml"));
        return new ResolvedUpdateScope(
                workspace.root(),
                member.directory(),
                member.path(),
                manifestPath,
                "zolt.lock",
                member.config(),
                lockfile,
                context.targetBlockers());
    }

    private Optional<WorkspaceOutdatedScope> rootScope(
            Workspace workspace,
            Optional<ZoltLockfile> lockfile,
            WorkspaceUpdateContext context) {
        if (!hasIndependentRootPlatforms(workspace)) {
            return Optional.empty();
        }
        String manifestPath = CanonicalUpdatePath.relative(workspace.root(), workspace.configPath());
        return Optional.of(new WorkspaceOutdatedScope(
                "workspace-root",
                manifestPath,
                "zolt.lock",
                workspace.config(),
                lockfile,
                context.repositoryConfigurations(),
                context.targetBlockers()));
    }

    private Optional<ResolvedWorkspaceUpdateScope> resolvedRootScope(
            Workspace workspace,
            Optional<ZoltLockfile> lockfile,
            WorkspaceUpdateContext context) {
        if (!hasIndependentRootPlatforms(workspace)) {
            return Optional.empty();
        }
        String manifestPath = CanonicalUpdatePath.relative(workspace.root(), workspace.configPath());
        return Optional.of(new ResolvedWorkspaceUpdateScope(
                workspace.root(),
                workspace.root(),
                "workspace-root",
                manifestPath,
                "zolt.lock",
                workspace.config(),
                lockfile,
                context.targetBlockers()));
    }

    private static boolean hasIndependentRootPlatforms(Workspace workspace) {
        if (workspace.config().platforms().isEmpty()) {
            return false;
        }
        Path configPath = workspace.configPath().toAbsolutePath().normalize();
        return workspace.members().stream()
                .map(member -> member.directory().resolve("zolt.toml").toAbsolutePath().normalize())
                .noneMatch(configPath::equals);
    }

    private Optional<ZoltLockfile> rootLockfile(Workspace workspace) {
        return scopes.readLockfile(workspace.root().resolve("zolt.lock"));
    }

    private Optional<Workspace> discoverCatalogWorkspace(Path start, Path mutationRoot) {
        try {
            return workspaceDiscovery.discover(start);
        } catch (WorkspaceConfigException exception) {
            if (start.toAbsolutePath().normalize().equals(mutationRoot)
                    && RetainedEmptyWorkspaceDomain.existsAt(mutationRoot)) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    private Optional<Workspace> discoverAutomationWorkspace(Path start) {
        try {
            return workspaceDiscovery.discover(start);
        } catch (WorkspaceConfigException exception) {
            Optional<Path> root = workspaceDiscovery.discoverRoot(start);
            if (root.isPresent()
                    && sameDirectory(start, root.orElseThrow())
                    && RetainedEmptyWorkspaceDomain.existsAt(root.orElseThrow())) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    private static boolean sameDirectory(Path left, Path right) {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private static String labelFor(Path start) {
        Path normalized = start.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        return name == null ? normalized.toString() : name.toString();
    }

    private static ZoltConfigException changedScope() {
        return new ZoltConfigException(
                "Dependency update scope changed while acquiring its mutation lock. Retry the command.");
    }
}
