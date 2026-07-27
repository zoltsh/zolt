package sh.zolt.cli.net;

import sh.zolt.config.NetworkConfig;
import sh.zolt.config.UserGlobalConfigException;
import sh.zolt.config.UserGlobalConfigParser;
import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.net.NetworkTransport;
import sh.zolt.net.ProxyConfiguration;
import sh.zolt.toolchain.ToolchainSyncService;
import sh.zolt.toolchain.install.ToolchainDownloadMirror;
import java.nio.file.Path;
import java.util.List;

/**
 * Composes Zolt's outbound network configuration for CLI commands: proxy settings from environment
 * variables and Java system properties, a custom CA bundle, and a Java toolchain download mirror.
 *
 * <p>Precedence per setting is environment variable over the {@code [network]} section of the user
 * global config ({@code ~/.zolt/config.toml}). All of this is transport configuration only and
 * never affects zolt.lock.
 */
public final class CommandNetwork {
    private static volatile NetworkTransport defaultTransport;

    private CommandNetwork() {
    }

    public static Path defaultConfigPath() {
        return UserGlobalDirectory.configFile();
    }

    /** Transport (proxy + CA trust) resolved from the default user global config and environment. */
    public static NetworkTransport defaultTransport() {
        NetworkTransport cached = defaultTransport;
        if (cached == null) {
            cached = transport(defaultConfigPath());
            defaultTransport = cached;
        }
        return cached;
    }

    /** Artifact download client configured with the default transport. */
    public static MavenRepositoryClient repositoryClient() {
        return new MavenRepositoryClient(defaultTransport());
    }

    /** Transport (proxy + CA trust) resolved from the given config path and environment. */
    public static NetworkTransport transport(Path configPath) {
        ProxyConfiguration proxy = ProxyConfiguration.fromEnvironment(System::getenv, System::getProperty);
        return NetworkTransport.create(proxy, caBundles(readNetwork(configPath)));
    }

    /** Java toolchain download mirror resolved from the given config path and environment. */
    public static ToolchainDownloadMirror toolchainMirror(Path configPath) {
        String environmentMirror = System.getenv(ToolchainDownloadMirror.MIRROR_ENV);
        if (environmentMirror != null && !environmentMirror.isBlank()) {
            return ToolchainDownloadMirror.of(environmentMirror);
        }
        return readNetwork(configPath).toolchainMirror()
                .map(ToolchainDownloadMirror::of)
                .orElse(ToolchainDownloadMirror.none());
    }

    /** Toolchain sync service whose downloads honor the proxy, CA, and mirror configuration. */
    public static ToolchainSyncService toolchainSyncService(Path configPath) {
        return ToolchainSyncService.withNetwork(transport(configPath), toolchainMirror(configPath));
    }

    private static List<Path> caBundles(NetworkConfig network) {
        String environmentBundle = System.getenv(NetworkTransport.CA_BUNDLE_ENV);
        if (environmentBundle != null && !environmentBundle.isBlank()) {
            return List.of(Path.of(environmentBundle.trim()));
        }
        return network.caBundle().map(List::of).orElse(List.of());
    }

    private static NetworkConfig readNetwork(Path configPath) {
        try {
            return new UserGlobalConfigParser().read(configPath).network();
        } catch (UserGlobalConfigException exception) {
            // Transport configuration must never block a build; fall back to environment-only settings.
            return NetworkConfig.none();
        }
    }
}
