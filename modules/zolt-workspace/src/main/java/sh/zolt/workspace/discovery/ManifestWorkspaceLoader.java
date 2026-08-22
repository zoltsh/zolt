package sh.zolt.workspace.discovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.adapter.LegacyDependencySection;
import sh.zolt.manifest.adapter.ProjectConfigRepositories;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveSharedConfiguration;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

/**
 * Loads a final-language workspace and projects it onto the legacy {@link Workspace} graph.
 *
 * <p>Membership expansion,
 * exclusion, effective composition, and selection are owned by {@link ManifestWorkspaceDiscovery};
 * this class only adapts the resulting {@link sh.zolt.workspace.discovery.DiscoveredWorkspace} into
 * the {@link Workspace}/{@link WorkspaceMember}/{@link WorkspaceProjectEdge} shape the pre-cut engine
 * consumes.
 *
 * <p>Freshness inputs are the discovery capture verbatim: it records the root manifest and every
 * member manifest byte-for-byte, adds a
 * {@code missing} entry for each expanded candidate directory without a manifest, and records
 * directory listings that stay outside {@link sh.zolt.workspace.service.WorkspaceInputs#digestsRelativeTo}
 * so that pure directory churn does not stale the lock (design §6.8).
 */
public final class ManifestWorkspaceLoader {
    private static final String MANIFEST = "zolt.toml";

    private final ManifestWorkspaceDiscovery discovery;
    private final EffectiveProjectConfigAdapter adapter;
    private final WorkspaceBuildOrderPlanner buildOrderPlanner;

    public ManifestWorkspaceLoader() {
        this(
                new ManifestWorkspaceDiscovery(),
                new EffectiveProjectConfigAdapter(),
                new WorkspaceBuildOrderPlanner());
    }

    ManifestWorkspaceLoader(
            ManifestWorkspaceDiscovery discovery,
            EffectiveProjectConfigAdapter adapter,
            WorkspaceBuildOrderPlanner buildOrderPlanner) {
        this.discovery = discovery;
        this.adapter = adapter;
        this.buildOrderPlanner = buildOrderPlanner;
    }

    /** Walks upward from {@code startDirectory} for the nearest final workspace root. */
    public Optional<Workspace> discover(Path startDirectory) {
        return discovery.discover(startDirectory).map(this::adapt);
    }

    /** Non-authoritative workspace-root lookup for choosing a mutation-lock scope. */
    public Optional<Path> discoverRoot(Path startDirectory) {
        return discovery.discoverRoot(startDirectory);
    }

    /** Loads the workspace rooted at {@code workspaceRoot}. */
    public Workspace load(Path workspaceRoot) {
        return adapt(discovery.load(workspaceRoot));
    }

    /**
     * Composes the complete effective workspace with {@code editedSource} standing in for the manifest
     * at {@code manifestPath}.
     *
     * <p>Runs the whole validation chain a resolving load runs — membership expansion, effective
     * composition, workspace-graph checks, and the effective-to-legacy adapter — and stops before
     * resolution. A caller validating an unwritten edit uses this and discards the result; the
     * captured inputs describe the edit rather than the filesystem and are never lock-freshness
     * evidence.
     */
    public Workspace compose(Path workspaceRoot, Path manifestPath, String editedSource) {
        return adapt(discovery.load(
                workspaceRoot,
                Map.of(manifestPath.toAbsolutePath().normalize(), editedSource)));
    }

    /** Projects an already-discovered final workspace onto the legacy graph. */
    public Workspace adapt(DiscoveredWorkspace discovered) {
        EffectiveWorkspace effective = discovered.effective();
        List<WorkspaceMember> members = members(discovered, effective);
        List<WorkspaceProjectEdge> edges = edges(members);
        return new Workspace(
                discovered.root(),
                discovered.manifestPath(),
                config(discovered, effective, members),
                members,
                edges,
                buildOrderPlanner.buildOrder(members, edges),
                discovered.inputs());
    }

    private List<WorkspaceMember> members(
            DiscoveredWorkspace discovered,
            EffectiveWorkspace effective) {
        List<WorkspaceMember> members = new ArrayList<>(effective.members().size());
        for (Map.Entry<WorkspaceMemberPath, EffectiveManifest> entry : effective.members().entrySet()) {
            WorkspaceMemberPath path = entry.getKey();
            DiscoveredWorkspaceMember discoveredMember = discovered.members().get(path);
            if (discoveredMember == null) {
                throw new WorkspaceConfigException(
                        "Effective workspace member `" + path + "` has no discovered directory.");
            }
            ProjectConfig config;
            try {
                config = adapter.adapt(
                        entry.getValue(),
                        EffectiveProjectConfigAdapter.workspacePaths(effective, path));
            } catch (IllegalArgumentException exception) {
                throw new WorkspaceConfigException(
                        "Workspace member `" + path + "` has an invalid "
                                + MANIFEST + ". " + exception.getMessage());
            }
            members.add(new WorkspaceMember(path.value(), discoveredMember.directory(), config));
        }
        return List.copyOf(members);
    }

    private static WorkspaceConfig config(
            DiscoveredWorkspace discovered,
            EffectiveWorkspace effective,
            List<WorkspaceMember> members) {
        Map<String, RepositorySettings> repositorySettings = repositorySettings(effective);
        Map<String, String> repositories = new LinkedHashMap<>();
        repositorySettings.forEach((id, settings) -> repositories.put(id, settings.url()));
        return new WorkspaceConfig(
                effective.workspace().name().value(),
                members.stream().map(WorkspaceMember::path).toList(),
                defaultMembers(discovered),
                Map.copyOf(repositories),
                platforms(effective.root()),
                repositorySettings,
                repositoryCredentials(effective.root()));
    }

    /**
     * The explicit default-member list the engine model carries. An implicit-all selection reports an
     * empty list, which the engine already reads as "every member" (design §6.2 {@code
     * [workspace.members].default}).
     */
    private static List<String> defaultMembers(DiscoveredWorkspace discovered) {
        if (discovered.selection().source() != WorkspaceMemberSelection.Source.EXPLICIT_DEFAULT) {
            return List.of();
        }
        return discovered.selection().members().stream().map(WorkspaceMemberPath::value).toList();
    }

    /**
     * Root-authored platforms only. A member may add its own {@code [platforms]} entry, which belongs
     * to that member and never to the workspace-level view (design §8.7 and the root/member merge).
     */
    private static Map<String, String> platforms(AuthoredManifest root) {
        Map<LocalId, VersionAliasValue> versions =
                root.versions().map(AuthoredVersionAliases::entries).orElseGet(Map::of);
        Map<String, String> platforms = new LinkedHashMap<>();
        root.platforms()
                .map(AuthoredPlatforms::entries)
                .orElseGet(Map::of)
                .forEach((coordinate, selector) ->
                        platforms.put(coordinate.value(), version(selector, versions, coordinate)));
        return Map.copyOf(platforms);
    }

    private static String version(
            PlatformSelector selector,
            Map<LocalId, VersionAliasValue> versions,
            DependencyCoordinate coordinate) {
        return switch (selector) {
            case PlatformSelector.FixedVersion fixed -> fixed.value();
            case PlatformSelector.VersionReference reference ->
                    alias(versions, reference.alias(), coordinate);
        };
    }

    private static String alias(
            Map<LocalId, VersionAliasValue> versions,
            LocalId alias,
            DependencyCoordinate coordinate) {
        VersionAliasValue value = versions.get(alias);
        if (value == null) {
            throw new WorkspaceConfigException(
                    "Workspace platform `" + coordinate
                            + "` references undefined version alias `" + alias + "`.");
        }
        return value.value();
    }

    private static Map<String, RepositorySettings> repositorySettings(EffectiveWorkspace effective) {
        return ProjectConfigRepositories.settings(
                rootRepositoryUniverse(effective).repositories());
    }

    /**
     * Root-authored credentials only. Members may add project-local credentials for their own
     * publication or generated tools (design §8.7); those stay member-local.
     */
    private static Map<String, RepositoryCredentialSettings> repositoryCredentials(
            AuthoredManifest root) {
        Map<String, RepositoryCredentialSettings> credentials = new LinkedHashMap<>();
        root.credentials()
                .map(AuthoredCredentials::entries)
                .orElseGet(Map::of)
                .forEach((id, credential) -> credentials.put(id.value(), switch (credential) {
                    case RepositoryCredential.BearerToken token ->
                            RepositoryCredentialSettings.token(id.value(), token.tokenEnvironment().value());
                    case RepositoryCredential.Basic basic ->
                            RepositoryCredentialSettings.basic(
                                    id.value(),
                                    basic.usernameEnvironment().value(),
                                    basic.passwordEnvironment().value());
                }));
        return Map.copyOf(credentials);
    }

    /**
     * The one root-owned repository universe. A member may not declare {@code [repositories]}
     * (design §8.7), so every member's effective view is the root universe verbatim.
     */
    private static EffectiveSharedConfiguration rootRepositoryUniverse(EffectiveWorkspace effective) {
        return effective.members().values().iterator().next().project().shared();
    }

    /**
     * Legacy project edges. The legacy edge model has exactly four scopes, so only the four lanes it
     * could express contribute: API exports on the compile scope, implementation on the compile scope
     * without export, test, and the two annotation-processor lanes.
     */
    private static List<WorkspaceProjectEdge> edges(List<WorkspaceMember> members) {
        List<WorkspaceProjectEdge> edges = new ArrayList<>();
        Map<String, WorkspaceMember> byPath = new LinkedHashMap<>();
        members.forEach(member -> byPath.put(member.path(), member));
        for (WorkspaceMember member : members) {
            add(edges, byPath, member, DependencyLane.API, "compile", true);
            add(edges, byPath, member, DependencyLane.IMPLEMENTATION, "compile", false);
            add(edges, byPath, member, DependencyLane.TEST, "test", false);
            add(edges, byPath, member, DependencyLane.PROCESSOR, "processor", false);
            add(edges, byPath, member, DependencyLane.TEST_PROCESSOR, "test-processor", false);
        }
        return List.copyOf(edges);
    }

    private static void add(
            List<WorkspaceProjectEdge> edges,
            Map<String, WorkspaceMember> byPath,
            WorkspaceMember from,
            DependencyLane lane,
            String scope,
            boolean exported) {
        String section = LegacyDependencySection.of(lane);
        for (Map.Entry<String, String> entry : new TreeMap<>(workspaceLane(from, lane)).entrySet()) {
            String coordinate = entry.getKey();
            WorkspaceMember target = byPath.get(entry.getValue());
            if (target == null) {
                throw new WorkspaceConfigException(
                        "Workspace dependency `" + coordinate + "` in member `" + from.path()
                                + "` points to `" + entry.getValue()
                                + "`, but that path is not a workspace member.");
            }
            DependencyMetadata metadata = from.config().dependencyMetadata()
                    .get(DependencyMetadata.key(section, coordinate));
            edges.add(new WorkspaceProjectEdge(
                    from.path(),
                    target.path(),
                    scope,
                    coordinate,
                    exported,
                    metadata != null && metadata.optional()));
        }
    }

    private static Map<String, String> workspaceLane(WorkspaceMember member, DependencyLane lane) {
        return switch (lane) {
            case API -> member.config().workspaceApiDependencies();
            case IMPLEMENTATION -> member.config().workspaceDependencies();
            case TEST -> member.config().workspaceTestDependencies();
            case PROCESSOR -> member.config().workspaceAnnotationProcessors();
            case TEST_PROCESSOR -> member.config().workspaceTestAnnotationProcessors();
            case RUNTIME, PROVIDED, DEV -> Map.of();
        };
    }
}
