package sh.zolt.config;

import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.project.RepositoryCredentialSettings;
import java.nio.file.Path;
import java.util.Map;

public record UserGlobalConfig(
        int version,
        Path configPath,
        Path cacheRoot,
        RepositoryExecutionConfig repository,
        Map<String, RepositoryOverlayConfig> repositoryOverlays,
        UserGlobalToolchainDefaults toolchainDefaults,
        UiConfig ui,
        NetworkConfig network,
        BuildCacheConfig buildCache,
        Map<String, RepositoryCredentialSettings> repositoryCredentials,
        UserGlobalConfigSources sources) {
    public UserGlobalConfig {
        repositoryOverlays = Map.copyOf(repositoryOverlays);
        repositoryCredentials = Map.copyOf(repositoryCredentials);
    }

    public static UserGlobalConfig defaults(Path configPath) {
        return new UserGlobalConfig(
                1,
                configPath,
                UserGlobalDirectory.artifactCache(),
                new RepositoryExecutionConfig(8, "platform"),
                Map.of("mavenLocal", new RepositoryOverlayConfig("mavenLocal", "maven-local", false)),
                UserGlobalToolchainDefaults.none(),
                new UiConfig("auto", "auto"),
                NetworkConfig.none(),
                BuildCacheConfig.disabled(),
                Map.of(),
                UserGlobalConfigSources.defaults());
    }
}
