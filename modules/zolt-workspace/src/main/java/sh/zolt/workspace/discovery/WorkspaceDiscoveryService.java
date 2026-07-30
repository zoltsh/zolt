package sh.zolt.workspace.discovery;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class WorkspaceDiscoveryService {
    private final WorkspaceConfigParser workspaceParser;
    private final ZoltTomlParser projectParser;
    private final WorkspaceBuildOrderPlanner buildOrderPlanner;

    public WorkspaceDiscoveryService() {
        this(new WorkspaceConfigParser(), new ZoltTomlParser(), new WorkspaceBuildOrderPlanner());
    }

    WorkspaceDiscoveryService(
            WorkspaceConfigParser workspaceParser,
            ZoltTomlParser projectParser,
            WorkspaceBuildOrderPlanner buildOrderPlanner) {
        this.workspaceParser = workspaceParser;
        this.projectParser = projectParser;
        this.buildOrderPlanner = buildOrderPlanner;
    }

    public Optional<Workspace> discover(Path startDirectory) {
        Path current = startDirectory.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }

        while (current != null) {
            Optional<Path> configPath = workspaceConfigPath(current);
            if (configPath.isPresent()) {
                return Optional.of(load(current, configPath.orElseThrow()));
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /**
     * Finds only the workspace root. Callers may use this non-authoritative lookup to choose the
     * mutation lock, then perform full discovery again while holding that lock.
     */
    public Optional<Path> discoverRoot(Path startDirectory) {
        return WorkspaceRootLocator.find(startDirectory, this);
    }

    public Workspace load(Path workspaceRoot) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path configPath = workspaceConfigPath(root)
                .orElseThrow(() -> new WorkspaceConfigException(
                        "Could not find workspace config at "
                                + root
                                + ". Add zolt.toml with [workspace] or create zolt-workspace.toml."));
        return load(root, configPath);
    }

    private Workspace load(Path root, Path configPath) {
        WorkspaceConfig config = workspaceConfig(configPath);
        List<WorkspaceMember> members = members(root, config);
        validateDefaultMembers(root, config.defaultMembers(), members);
        List<WorkspaceProjectEdge> edges = workspaceProjectEdges(root, members);
        List<String> buildOrder = buildOrderPlanner.buildOrder(members, edges);
        return new Workspace(root, configPath, config, members, edges, buildOrder);
    }

    private WorkspaceConfig workspaceConfig(Path configPath) {
        if (WorkspaceConfigParser.ROOT_CONFIG_FILE.equals(configPath.getFileName().toString())) {
            return workspaceParser.parseRootConfig(configPath);
        }
        return workspaceParser.parse(configPath);
    }

    Optional<Path> workspaceConfigPath(Path root) {
        Path legacyConfig = root.resolve(WorkspaceConfigParser.WORKSPACE_FILE).normalize();
        Path rootConfig = root.resolve(WorkspaceConfigParser.ROOT_CONFIG_FILE).normalize();
        boolean hasLegacyConfig = Files.isRegularFile(legacyConfig);
        boolean hasRootWorkspaceConfig =
                Files.isRegularFile(rootConfig) && workspaceParser.hasWorkspaceSection(rootConfig);
        if (hasLegacyConfig && hasRootWorkspaceConfig) {
            throw new WorkspaceConfigException(
                    "Ambiguous workspace config at "
                            + root
                            + ". Use either zolt.toml with [workspace] or zolt-workspace.toml, not both.");
        }
        if (hasRootWorkspaceConfig) {
            return Optional.of(rootConfig);
        }
        if (hasLegacyConfig) {
            return Optional.of(legacyConfig);
        }
        return Optional.empty();
    }

    private List<WorkspaceMember> members(Path root, WorkspaceConfig config) {
        List<WorkspaceMember> members = new ArrayList<>();
        Set<String> memberPaths = new LinkedHashSet<>();
        Map<String, String> coordinates = new LinkedHashMap<>();
        for (String declaredPath : config.members()) {
            ResolvedMemberPath resolved = resolveMemberPath(root, declaredPath, "[workspace].members");
            if (!memberPaths.add(resolved.path())) {
                throw new WorkspaceConfigException(
                        "Duplicate workspace member `" + resolved.path() + "` after path normalization.");
            }

            Path projectConfigPath = resolved.directory().resolve("zolt.toml");
            if (!Files.isRegularFile(projectConfigPath)) {
                throw new WorkspaceConfigException(
                        "Workspace member `"
                                + resolved.path()
                                + "` must contain zolt.toml at "
                                + projectConfigPath
                                + ".");
            }

            ProjectConfig projectConfig = parseProjectConfig(resolved.path(), projectConfigPath);
            String coordinate = projectConfig.project().group() + ":" + projectConfig.project().name();
            String existingMember = coordinates.putIfAbsent(coordinate, resolved.path());
            if (existingMember != null) {
                throw new WorkspaceConfigException(
                        "Workspace member coordinate `"
                                + coordinate
                                + "` is used by both `"
                                + existingMember
                                + "` and `"
                                + resolved.path()
                                + "`. Give each member a unique [project].group:[project].name.");
            }
            members.add(new WorkspaceMember(resolved.path(), resolved.directory(), projectConfig));
        }
        return List.copyOf(members);
    }

    private ProjectConfig parseProjectConfig(String memberPath, Path projectConfigPath) {
        try {
            return projectParser.parse(projectConfigPath);
        } catch (ZoltConfigException exception) {
            throw new WorkspaceConfigException(
                    "Workspace member `" + memberPath + "` has an invalid zolt.toml. " + exception.getMessage());
        }
    }

    private void validateDefaultMembers(
            Path root,
            List<String> defaultMembers,
            List<WorkspaceMember> members) {
        Set<String> memberPaths = new LinkedHashSet<>();
        for (WorkspaceMember member : members) {
            memberPaths.add(member.path());
        }
        Set<String> normalizedDefaults = new LinkedHashSet<>();
        for (String declaredPath : defaultMembers) {
            ResolvedMemberPath resolved = resolveMemberPath(root, declaredPath, "[workspace].defaultMembers");
            if (!normalizedDefaults.add(resolved.path())) {
                throw new WorkspaceConfigException(
                        "Duplicate workspace default member `" + resolved.path() + "` after path normalization.");
            }
            if (!memberPaths.contains(resolved.path())) {
                throw new WorkspaceConfigException(
                        "Workspace default member `"
                                + resolved.path()
                                + "` must also be listed in [workspace].members.");
            }
        }
    }

    private List<WorkspaceProjectEdge> workspaceProjectEdges(Path root, List<WorkspaceMember> members) {
        Map<String, WorkspaceMember> membersByPath = new LinkedHashMap<>();
        for (WorkspaceMember member : members) {
            membersByPath.put(member.path(), member);
        }

        List<WorkspaceProjectEdge> edges = new ArrayList<>();
        for (WorkspaceMember member : members) {
            addWorkspaceProjectEdges(
                    root,
                    membersByPath,
                    edges,
                    member,
                    "compile",
                    member.config().workspaceApiDependencies(),
                    "[api.dependencies]",
                    true);
            addWorkspaceProjectEdges(
                    root,
                    membersByPath,
                    edges,
                    member,
                    "compile",
                    member.config().workspaceDependencies(),
                    "[dependencies]",
                    false);
            addWorkspaceProjectEdges(
                    root,
                    membersByPath,
                    edges,
                    member,
                    "test",
                    member.config().workspaceTestDependencies(),
                    "[test.dependencies]",
                    false);
            addWorkspaceProjectEdges(
                    root,
                    membersByPath,
                    edges,
                    member,
                    "processor",
                    member.config().workspaceAnnotationProcessors(),
                    "[annotationProcessors]",
                    false);
            addWorkspaceProjectEdges(
                    root,
                    membersByPath,
                    edges,
                    member,
                    "test-processor",
                    member.config().workspaceTestAnnotationProcessors(),
                    "[test.annotationProcessors]",
                    false);
        }
        return List.copyOf(edges);
    }

    private static void addWorkspaceProjectEdges(
            Path root,
            Map<String, WorkspaceMember> membersByPath,
            List<WorkspaceProjectEdge> edges,
            WorkspaceMember from,
            String scope,
            Map<String, String> dependencies,
            String section,
            boolean exported) {
        for (Map.Entry<String, String> dependency : new TreeMap<>(dependencies).entrySet()) {
            String coordinate = dependency.getKey();
            String declaredPath = dependency.getValue();
            ResolvedMemberPath resolved = resolveMemberPath(root, declaredPath, section + "." + coordinate + ".workspace");
            WorkspaceMember targetByPath = membersByPath.get(resolved.path());
            if (targetByPath == null) {
                throw new WorkspaceConfigException(
                        "Workspace dependency `"
                                + coordinate
                                + "` in member `"
                                + from.path()
                                + "` points to `"
                                + resolved.path()
                                + "`, but that path is not listed in [workspace].members.");
            }
            if (from.path().equals(targetByPath.path())) {
                throw new WorkspaceConfigException(
                        "Workspace member `"
                                + from.path()
                                + "` cannot depend on itself through `"
                                + coordinate
                                + "`.");
            }
            String targetCoordinate = coordinate(targetByPath);
            if (!targetCoordinate.equals(coordinate)) {
                throw new WorkspaceConfigException(
                        "Workspace dependency `"
                                + coordinate
                                + "` in member `"
                                + from.path()
                                + "` points to `"
                                + resolved.path()
                                + "`, whose project coordinate is `"
                                + targetCoordinate
                                + "`. Update the dependency key or workspace path so they match.");
            }
            PackageMode targetMode = targetByPath.config().packageSettings().mode();
            if (targetMode != PackageMode.THIN) {
                throw new WorkspaceConfigException(
                        "Workspace dependency `"
                                + coordinate
                                + "` in member `"
                                + from.path()
                                + "` targets member `"
                                + targetByPath.path()
                                + "`, whose package mode is `"
                                + targetMode.configValue()
                                + "`. Executable, application, WAR, and BOM packaging is not a reusable library artifact. "
                                + "Split shared code into a separate workspace member with package mode `thin`, then depend on that member.");
            }
            DependencyMetadata metadata = from.config().dependencyMetadata()
                    .get(DependencyMetadata.key(
                            section.substring(1, section.length() - 1),
                            coordinate));
            edges.add(new WorkspaceProjectEdge(
                    from.path(),
                    targetByPath.path(),
                    scope,
                    coordinate,
                    exported,
                    metadata != null && metadata.optional()));
        }
    }

    private static String coordinate(WorkspaceMember member) {
        return member.config().project().group() + ":" + member.config().project().name();
    }

    private static ResolvedMemberPath resolveMemberPath(Path root, String declaredPath, String field) {
        Path directory;
        try {
            directory = ProjectPaths.existingRoot(root, field, declaredPath);
        } catch (ProjectPathException exception) {
            throw new WorkspaceConfigException(
                    "Invalid workspace member path `"
                            + declaredPath
                            + "` in "
                            + field
                            + ". "
                            + exception.getMessage());
        }
        Path relative = root.relativize(directory);
        String normalizedPath = relative.toString().replace('\\', '/');
        if (normalizedPath.isBlank()) {
            normalizedPath = ".";
        }
        return new ResolvedMemberPath(normalizedPath, directory);
    }

    private record ResolvedMemberPath(
            String path,
            Path directory) {
    }
}
