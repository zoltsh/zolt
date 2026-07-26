package sh.zolt.workspace.resolve;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositorySettings;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorkspacePolicyMerger {
    ProjectConfig merge(Workspace workspace, WorkspaceMember member) {
        ProjectConfig config = member.config();
        Map<String, String> repositories = mergedPolicy(
                "repository",
                workspace,
                member,
                workspace.config().repositories(),
                config.repositories());
        Map<String, RepositorySettings> repositorySettings =
                mergedRepositorySettings(workspace, member, repositories, config.repositorySettings());
        return new ProjectConfig(
                config.project(),
                repositories,
                repositorySettings,
                config.repositoryCredentials(),
                config.versionAliases(),
                mergedPolicy(
                        "platform",
                        workspace,
                        member,
                        workspace.config().platforms(),
                        config.platforms()),
                config.apiDependencies(),
                config.managedApiDependencies(),
                config.workspaceApiDependencies(),
                config.dependencies(),
                config.managedDependencies(),
                config.workspaceDependencies(),
                config.runtimeDependencies(),
                config.managedRuntimeDependencies(),
                config.providedDependencies(),
                config.managedProvidedDependencies(),
                config.devDependencies(),
                config.managedDevDependencies(),
                config.testDependencies(),
                config.managedTestDependencies(),
                config.workspaceTestDependencies(),
                config.annotationProcessors(),
                config.managedAnnotationProcessors(),
                config.workspaceAnnotationProcessors(),
                config.testAnnotationProcessors(),
                config.managedTestAnnotationProcessors(),
                config.workspaceTestAnnotationProcessors(),
                config.dependencyPolicy(),
                config.build(),
                config.nativeSettings(),
                config.compilerSettings(),
                config.packageSettings(),
                config.frameworkSettings(),
                config.dependencyMetadata());
    }

    private static Map<String, String> mergedPolicy(
            String kind,
            Workspace workspace,
            WorkspaceMember member,
            Map<String, String> workspaceValues,
            Map<String, String> memberValues) {
        Map<String, String> merged = new LinkedHashMap<>(workspaceValues);
        for (Map.Entry<String, String> entry : memberValues.entrySet()) {
            String existing = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new ResolveException(
                        "Workspace "
                                + kind
                                + " `"
                                + entry.getKey()
                                + "` has value `"
                                + existing
                                + "` in "
                                + workspace.configPath()
                                + " but member `"
                                + member.path()
                                + "` declares `"
                                + entry.getValue()
                                + "`. Make the values match or remove the member override.");
            }
        }
        return merged;
    }

    private static Map<String, RepositorySettings> mergedRepositorySettings(
            Workspace workspace,
            WorkspaceMember member,
            Map<String, String> repositories,
            Map<String, RepositorySettings> memberSettings) {
        Map<String, RepositorySettings> settings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : repositories.entrySet()) {
            settings.put(entry.getKey(), RepositorySettings.unauthenticated(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, RepositorySettings> entry : memberSettings.entrySet()) {
            String repositoryId = entry.getKey();
            RepositorySettings repository = entry.getValue();
            String effectiveUrl = repositories.get(repositoryId);
            if (!repositoryId.equals(repository.id())
                    || effectiveUrl == null
                    || !effectiveUrl.equals(repository.url())) {
                throw incompatibleRepositorySettings(workspace, member, repositoryId, repository, effectiveUrl);
            }
            settings.put(repositoryId, repository);
        }
        return settings;
    }

    private static ResolveException incompatibleRepositorySettings(
            Workspace workspace,
            WorkspaceMember member,
            String repositoryId,
            RepositorySettings repository,
            String effectiveUrl) {
        return new ResolveException(
                "Workspace repository `"
                        + repositoryId
                        + "` has incompatible structured settings for member `"
                        + member.path()
                        + "`: repository id `"
                        + repository.id()
                        + "`, URL `"
                        + repository.url()
                        + "`, effective URL `"
                        + (effectiveUrl == null ? "<missing>" : effectiveUrl)
                        + "`. Make the repository id and URL match "
                        + workspace.configPath()
                        + " without removing its credential reference.");
    }
}
