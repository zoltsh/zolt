package sh.zolt.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositoryConfiguration;
import sh.zolt.project.RepositorySettings;

public record WorkspaceConfig(
        String name,
        List<String> members,
        List<String> defaultMembers,
        Map<String, String> repositories,
        Map<String, String> platforms,
        Map<String, RepositorySettings> repositorySettings,
        Map<String, RepositoryCredentialSettings> repositoryCredentials) implements RepositoryConfiguration {
    public WorkspaceConfig(
            String name,
            List<String> members,
            List<String> defaultMembers,
            Map<String, String> repositories,
            Map<String, String> platforms) {
        this(
                name,
                members,
                defaultMembers,
                repositories,
                platforms,
                unauthenticated(repositories),
                Map.of());
    }

    public WorkspaceConfig {
        members = List.copyOf(members);
        defaultMembers = List.copyOf(defaultMembers);
        repositories = Collections.unmodifiableMap(new LinkedHashMap<>(repositories));
        platforms = Collections.unmodifiableMap(new LinkedHashMap<>(platforms));
        repositorySettings = Collections.unmodifiableMap(new LinkedHashMap<>(repositorySettings));
        repositoryCredentials =
                Collections.unmodifiableMap(new LinkedHashMap<>(repositoryCredentials));
    }

    public WorkspaceConfig withPlatforms(Map<String, String> updatedPlatforms) {
        return new WorkspaceConfig(
                name,
                members,
                defaultMembers,
                repositories,
                updatedPlatforms,
                repositorySettings,
                repositoryCredentials);
    }

    private static Map<String, RepositorySettings> unauthenticated(
            Map<String, String> repositories) {
        Map<String, RepositorySettings> settings = new LinkedHashMap<>();
        repositories.forEach((id, url) ->
                settings.put(id, RepositorySettings.unauthenticated(id, url)));
        return settings;
    }
}
