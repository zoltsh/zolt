package sh.zolt.cli.command.dependency;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.update.OutdatedScope;
import sh.zolt.update.OutdatedScopes;
import sh.zolt.update.UpdateTargetCatalog;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Resolves the manifests one {@code outdated} or {@code update} invocation reports on and mutates.
 *
 * <p>Every scope is one authored manifest with one source location, so a workspace produces one
 * scope per member plus a root scope when the root manifest declares surfaces of its own. A member
 * never re-reports an inherited root value, which is what makes a schema-v2 target ID identify
 * exactly one declaration (design §4.5 named maps, §20.1).
 */
final class DependencyUpdateScopeResolver {
    private static final String MANIFEST = "zolt.toml";
    private static final String LOCKFILE = "zolt.lock";

    private final OutdatedScopes scopes;
    private final ManifestWorkspaceLoader workspaceLoader;
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();

    DependencyUpdateScopeResolver() {
        this(new OutdatedScopes(), new ManifestWorkspaceLoader());
    }

    DependencyUpdateScopeResolver(OutdatedScopes scopes, ManifestWorkspaceLoader workspaceLoader) {
        this.scopes = scopes;
        this.workspaceLoader = workspaceLoader;
    }

    /**
     * Report scopes for {@code zolt outdated}. Schema v2 requires strictly canonical NFC POSIX paths;
     * schema v1 keeps reporting a path it can spell but not certify.
     */
    List<OutdatedScope> reportScopes(Path start, int schemaVersion) {
        BiFunction<Path, Path, String> relative = schemaVersion == 2
                ? CanonicalUpdatePath::relative
                : CanonicalUpdatePath::rawRelative;
        Optional<Workspace> discovered = workspaceLoader.discover(start);
        if (discovered.isEmpty() || reportsStandalone(discovered.orElseThrow(), start, schemaVersion)) {
            return List.of(scopes.fromDirectory(labelFor(start), start));
        }
        Workspace workspace = discovered.orElseThrow();
        Optional<ZoltLockfile> lockfile = scopes.readLockfile(workspace.root().resolve(LOCKFILE));
        List<OutdatedScope> reportScopes = new ArrayList<>();
        rootManifest(workspace, relative).ifPresent(root -> reportScopes.add(new OutdatedScope(
                "workspace-root",
                root,
                LOCKFILE,
                manifestOf(workspace.configPath()),
                workspace.config(),
                lockfile)));
        for (WorkspaceMember member : selectedMembers(workspace, start)) {
            reportScopes.add(new OutdatedScope(
                    member.path(),
                    relative.apply(workspace.root(), member.directory().resolve(MANIFEST)),
                    LOCKFILE,
                    manifestOf(member.directory().resolve(MANIFEST)),
                    member.config(),
                    lockfile));
        }
        return List.copyOf(reportScopes);
    }

    /** Every mutable scope under a confirmed mutation root, for exact target selection. */
    List<ResolvedUpdateScope> catalogScopes(Path start, Path confirmedMutationRoot) {
        Path mutationRoot = confirmedMutationRoot.toAbsolutePath().normalize();
        Optional<Workspace> discovered = workspaceLoader.discover(start);
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
                    MANIFEST,
                    LOCKFILE,
                    scope.manifest(),
                    scope.discovery(),
                    Optional.empty(),
                    scope.lockfile(),
                    false));
        }
        Workspace workspace = discovered.orElseThrow();
        if (!sameDirectory(workspace.root(), mutationRoot)) {
            throw changedScope();
        }
        Optional<ZoltLockfile> lockfile = scopes.readLockfile(workspace.root().resolve(LOCKFILE));
        List<ResolvedUpdateScope> resolved = new ArrayList<>();
        rootManifest(workspace, CanonicalUpdatePath::relative).ifPresent(root -> resolved.add(
                new ResolvedUpdateScope(
                        workspace.root(),
                        workspace.root(),
                        "workspace-root",
                        root,
                        LOCKFILE,
                        manifestOf(workspace.configPath()),
                        workspace.config(),
                        Optional.empty(),
                        lockfile,
                        true)));
        for (WorkspaceMember member : workspace.members()) {
            resolved.add(memberScope(workspace, member, lockfile, CanonicalUpdatePath::relative));
        }
        return List.copyOf(resolved);
    }

    /** The single scope a policy-driven {@code zolt update} mutates. */
    ResolvedUpdateScope policyScope(Path start, Path confirmedMutationRoot) {
        Path project = start.toAbsolutePath().normalize();
        Path mutationRoot = confirmedMutationRoot.toAbsolutePath().normalize();
        Optional<Workspace> discovered = workspaceLoader.discover(start);
        if (discovered.isEmpty()) {
            if (!project.equals(mutationRoot)) {
                throw changedScope();
            }
            OutdatedScope scope = scopes.fromDirectoryWithoutLock(labelFor(project), project);
            return new ResolvedUpdateScope(
                    mutationRoot,
                    project,
                    scope.label(),
                    MANIFEST,
                    LOCKFILE,
                    scope.manifest(),
                    scope.discovery(),
                    Optional.empty(),
                    Optional.empty(),
                    false);
        }
        Workspace workspace = discovered.orElseThrow();
        if (!sameDirectory(workspace.root(), mutationRoot)) {
            throw changedScope();
        }
        WorkspaceMember member = workspace.members().stream()
                .filter(candidate -> sameDirectory(candidate.directory(), project))
                .findFirst()
                .orElseThrow(() -> new ZoltConfigException(
                        "Dependency updates require a standalone project or declared workspace member."));
        return memberScope(workspace, member, Optional.empty(), CanonicalUpdatePath::rawRelative);
    }

    private ResolvedUpdateScope memberScope(
            Workspace workspace,
            WorkspaceMember member,
            Optional<ZoltLockfile> lockfile,
            BiFunction<Path, Path, String> relative) {
        Path manifestPath = member.directory().resolve(MANIFEST);
        return new ResolvedUpdateScope(
                workspace.root(),
                member.directory(),
                member.path(),
                relative.apply(workspace.root(), manifestPath),
                LOCKFILE,
                manifestOf(manifestPath),
                member.config(),
                Optional.of(member.config()),
                lockfile,
                false);
    }

    /**
     * The root manifest path when the root declares targets nobody else owns. A root that is also a
     * member is reported once, through that member.
     */
    private Optional<String> rootManifest(Workspace workspace, BiFunction<Path, Path, String> relative) {
        Path configPath = workspace.configPath().toAbsolutePath().normalize();
        boolean ownedByMember = workspace.members().stream()
                .map(member -> member.directory().resolve(MANIFEST).toAbsolutePath().normalize())
                .anyMatch(configPath::equals);
        if (ownedByMember) {
            return Optional.empty();
        }
        String path = relative.apply(workspace.root(), workspace.configPath());
        return catalog.references(manifestOf(workspace.configPath()), path).isEmpty()
                ? Optional.empty()
                : Optional.of(path);
    }

    /**
     * A directory a workspace never expanded into a member is its own project. Schema v1 reports it
     * that way; schema v2 refuses, because automation needs a path canonical under one mutation root.
     */
    private static boolean reportsStandalone(Workspace workspace, Path start, int schemaVersion) {
        if (schemaVersion == 2 || sameDirectory(start, workspace.root())) {
            return false;
        }
        return workspace.members().stream()
                .noneMatch(candidate -> sameDirectory(start, candidate.directory()));
    }

    private List<WorkspaceMember> selectedMembers(Workspace workspace, Path start) {
        if (sameDirectory(start, workspace.root())) {
            return workspace.members();
        }
        return List.of(workspace.members().stream()
                .filter(candidate -> sameDirectory(start, candidate.directory()))
                .findFirst()
                .orElseThrow(() -> new ZoltConfigException(
                        "Dependency reports require a standalone project, workspace root, or declared workspace member.")));
    }

    private AuthoredManifest manifestOf(Path manifestPath) {
        return scopes.manifest(manifestPath);
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
