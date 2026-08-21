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
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.adapter.LegacyDependencySection;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveSharedConfiguration;
import sh.zolt.manifest.effective.EffectiveValue;
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
 * <p>This is the final-language twin of {@link WorkspaceDiscoveryService}. Membership expansion,
 * exclusion, effective composition, and selection are owned by {@link ManifestWorkspaceDiscovery};
 * this class only adapts the resulting {@link sh.zolt.workspace.discovery.DiscoveredWorkspace} into
 * the {@link Workspace}/{@link WorkspaceMember}/{@link WorkspaceProjectEdge} shape the pre-cut engine
 * consumes.
 *
 * <p>Freshness inputs are the discovery capture verbatim. The final capture is a strict superset of
 * the legacy one: it still records the root manifest and every member manifest byte-for-byte, adds a
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
        EffectiveSharedConfiguration shared = rootShared(effective);
        Map<String, RepositorySettings> repositorySettings =
                repositorySettings(effective);
        Map<String, String> repositories = new LinkedHashMap<>();
        repositorySettings.forEach((id, settings) -> repositories.put(id, settings.url()));
        return new WorkspaceConfig(
                effective.workspace().name().value(),
                members.stream().map(WorkspaceMember::path).toList(),
                defaultMembers(discovered),
                Map.copyOf(repositories),
                platforms(shared),
                repositorySettings,
                repositoryCredentials(effective));
    }

    /**
     * The legacy explicit default-member list. An implicit-all final selection has no legacy analogue
     * beyond "no {@code defaultMembers} were declared", which the legacy selector already reads as
     * "every member" (design §6.2 {@code default}).
     */
    private static List<String> defaultMembers(DiscoveredWorkspace discovered) {
        if (discovered.selection().source() != WorkspaceMemberSelection.Source.EXPLICIT_DEFAULT) {
            return List.of();
        }
        return discovered.selection().members().stream().map(WorkspaceMemberPath::value).toList();
    }

    private static Map<String, String> platforms(EffectiveSharedConfiguration shared) {
        Map<String, String> platforms = new LinkedHashMap<>();
        for (Map.Entry<DependencyCoordinate, EffectiveValue<PlatformSelector>> entry
                : shared.platforms().entrySet()) {
            platforms.put(entry.getKey().value(), version(entry.getValue().value(), shared));
        }
        return Map.copyOf(platforms);
    }

    private static String version(PlatformSelector selector, EffectiveSharedConfiguration shared) {
        return switch (selector) {
            case PlatformSelector.FixedVersion fixed -> fixed.value();
            case PlatformSelector.VersionReference reference -> alias(shared, reference.alias());
        };
    }

    private static String alias(EffectiveSharedConfiguration shared, LocalId alias) {
        EffectiveValue<VersionAliasValue> value = shared.versions().get(alias);
        if (value == null) {
            throw new WorkspaceConfigException(
                    "Workspace platform references undefined version alias `" + alias + "`.");
        }
        return value.value().value();
    }

    private static Map<String, RepositorySettings> repositorySettings(EffectiveWorkspace effective) {
        EffectiveSharedConfiguration shared = rootShared(effective);
        Map<String, RepositorySettings> settings = new LinkedHashMap<>();
        for (LocalId id : shared.repositories().lookupOrder().value()) {
            settings.put(id.value(), repository(shared, id));
        }
        return Map.copyOf(settings);
    }

    private static RepositorySettings repository(EffectiveSharedConfiguration shared, LocalId id) {
        if (id.value().equals("central")) {
            return new RepositorySettings(
                    id.value(),
                    shared.repositories().central().value().repository().orElseThrow().url().value(),
                    Optional.empty());
        }
        EffectiveValue<sh.zolt.manifest.DependencyRepository> repository =
                shared.repositories().named().get(id);
        if (repository == null) {
            throw new WorkspaceConfigException(
                    "Workspace repository lookup order names undefined repository `" + id + "`.");
        }
        return new RepositorySettings(
                id.value(),
                repository.value().url().value(),
                repository.value().credentials().map(LocalId::value));
    }

    private static Map<String, RepositoryCredentialSettings> repositoryCredentials(
            EffectiveWorkspace effective) {
        Map<String, RepositoryCredentialSettings> credentials = new LinkedHashMap<>();
        rootShared(effective).credentials().forEach((id, credential) ->
                credentials.put(id.value(), switch (credential.value()) {
                    case sh.zolt.manifest.RepositoryCredential.BearerToken token ->
                            RepositoryCredentialSettings.token(id.value(), token.tokenEnvironment().value());
                    case sh.zolt.manifest.RepositoryCredential.Basic basic ->
                            RepositoryCredentialSettings.basic(
                                    id.value(),
                                    basic.usernameEnvironment().value(),
                                    basic.passwordEnvironment().value());
                }));
        return Map.copyOf(credentials);
    }

    /**
     * Root-owned shared configuration. Every member inherits the same effective repository universe,
     * credentials, versions, and platforms (design §8.7 and §4.5), so the first member's shared view
     * is the workspace-level view.
     */
    private static EffectiveSharedConfiguration rootShared(EffectiveWorkspace effective) {
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
