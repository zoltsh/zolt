package sh.zolt.cli.command.dependency;

import sh.zolt.project.RepositoryConfiguration;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.update.OutdatedSurface;
import sh.zolt.update.UpdateTarget;
import sh.zolt.update.UpdateTargetCatalog;
import sh.zolt.update.UpdateTargetId;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workspace-wide context needed to keep schema-v2 discovery and exact routing honest. */
record WorkspaceUpdateContext(
        Map<UpdateTargetId, String> targetBlockers,
        List<RepositoryConfiguration> repositoryConfigurations,
        Map<String, ProjectConfig> effectiveMemberConfigs) {
    private static final String PLATFORM_PREFIX = "[platforms].";

    WorkspaceUpdateContext {
        targetBlockers = targetBlockers == null ? Map.of() : Map.copyOf(targetBlockers);
        repositoryConfigurations = repositoryConfigurations == null
                ? List.of()
                : List.copyOf(repositoryConfigurations);
        effectiveMemberConfigs = effectiveMemberConfigs == null ? Map.of() : Map.copyOf(effectiveMemberConfigs);
    }

    static WorkspaceUpdateContext from(Workspace workspace) {
        UpdateTargetCatalog catalog = new UpdateTargetCatalog();
        String rootManifest = CanonicalUpdatePath.relative(workspace.root(), workspace.configPath());
        Map<String, UpdateTarget> rootsByCoordinate = new LinkedHashMap<>();
        catalog.collect(workspace.config(), rootManifest, "zolt.lock").stream()
                .filter(target -> target.surface() == OutdatedSurface.PLATFORM)
                .forEach(target -> rootsByCoordinate.put(target.identifier(), target));

        Map<String, List<MemberTarget>> mirrors = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            if (ownsRootManifest(workspace, member)) {
                continue;
            }
            String manifest = CanonicalUpdatePath.relative(
                    workspace.root(), member.directory().resolve("zolt.toml"));
            for (UpdateTarget target : catalog.collect(member.config(), manifest, "zolt.lock")) {
                for (String coordinate : mirroredCoordinates(target, rootsByCoordinate)) {
                    mirrors.computeIfAbsent(coordinate, ignored -> new ArrayList<>())
                            .add(new MemberTarget(member.path(), target));
                }
            }
        }

        Map<UpdateTargetId, String> blockers = new LinkedHashMap<>();
        mirrors.forEach((coordinate, targets) -> {
            String members = String.join(", ", targets.stream()
                    .map(MemberTarget::member)
                    .distinct()
                    .sorted()
                    .toList());
            String blocker = "Platform `" + coordinate + "` is declared in workspace-root [platforms] and member(s) "
                    + members
                    + "; consolidate the declaration under workspace-root [platforms] before using exact updates.";
            blockers.put(rootsByCoordinate.get(coordinate).targetId(), blocker);
            targets.forEach(target -> blockers.put(target.target().targetId(), blocker));
        });

        WorkspaceMemberPolicyResolver policyResolver = new WorkspaceMemberPolicyResolver();
        Map<RepositoryConfigurationKey, RepositoryConfiguration> distinctRepositories = new LinkedHashMap<>();
        Map<String, ProjectConfig> effectiveConfigs = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            ProjectConfig effective = policyResolver.merge(workspace, member);
            effectiveConfigs.put(member.path(), effective);
            distinctRepositories.putIfAbsent(RepositoryConfigurationKey.from(effective), effective);
        }
        List<RepositoryConfiguration> repositories = List.copyOf(distinctRepositories.values());
        if (repositories.isEmpty()) {
            repositories = List.of(workspace.config());
        }
        return new WorkspaceUpdateContext(blockers, repositories, effectiveConfigs);
    }

    ProjectConfig effectiveConfig(WorkspaceMember member) {
        ProjectConfig effective = effectiveMemberConfigs.get(member.path());
        if (effective == null) {
            throw new IllegalStateException("Missing effective update configuration for workspace member "
                    + member.path() + ".");
        }
        return effective;
    }

    private static List<String> mirroredCoordinates(
            UpdateTarget target,
            Map<String, UpdateTarget> rootsByCoordinate) {
        if (target.surface() == OutdatedSurface.PLATFORM && rootsByCoordinate.containsKey(target.identifier())) {
            return List.of(target.identifier());
        }
        if (target.surface() != OutdatedSurface.VERSION_ALIAS) {
            return List.of();
        }
        return target.governs().stream()
                .filter(label -> label.startsWith(PLATFORM_PREFIX))
                .map(label -> label.substring(PLATFORM_PREFIX.length()))
                .filter(rootsByCoordinate::containsKey)
                .distinct()
                .toList();
    }

    private static boolean ownsRootManifest(Workspace workspace, WorkspaceMember member) {
        Path rootConfig = workspace.configPath().toAbsolutePath().normalize();
        Path memberConfig = member.directory().resolve("zolt.toml").toAbsolutePath().normalize();
        return rootConfig.equals(memberConfig);
    }

    private record MemberTarget(String member, UpdateTarget target) {
    }

    private record RepositoryConfigurationKey(
            Map<String, RepositorySettings> settings,
            Map<String, RepositoryCredentialSettings> credentials) {
        static RepositoryConfigurationKey from(RepositoryConfiguration configuration) {
            return new RepositoryConfigurationKey(
                    Map.copyOf(configuration.repositorySettings()),
                    Map.copyOf(configuration.repositoryCredentials()));
        }
    }
}
