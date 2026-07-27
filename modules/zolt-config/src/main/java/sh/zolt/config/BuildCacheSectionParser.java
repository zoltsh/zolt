package sh.zolt.config;

import static sh.zolt.config.UserGlobalConfigToml.booleanOrDefault;
import static sh.zolt.config.UserGlobalConfigToml.positiveIntOrDefault;
import static sh.zolt.config.UserGlobalConfigToml.resolveConfigRelativePath;
import static sh.zolt.config.UserGlobalConfigToml.stringOrNull;
import static sh.zolt.config.UserGlobalConfigToml.validateKeys;

import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.project.RepositoryCredentialSettings;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.tomlj.TomlTable;

/**
 * Parses the user-global {@code [buildCache]} section (local settings, optional remote tier) and the
 * {@code [repositoryCredentials]} it references, and validates that a remote credential id resolves.
 * Split out of {@link UserGlobalConfigParser} so the build-cache config surface owns its own keys and
 * diagnostics.
 */
final class BuildCacheSectionParser {
    private static final Set<String> BUILD_CACHE_KEYS = Set.of("enabled", "dir", "maxSizeMb", "remote");
    private static final Set<String> BUILD_CACHE_REMOTE_KEYS = Set.of("url", "credentials", "push");
    private static final Set<String> CREDENTIAL_KEYS = Set.of("usernameEnv", "passwordEnv", "tokenEnv");
    private static final int DEFAULT_BUILD_CACHE_MAX_SIZE_MB = 2048;

    private BuildCacheSectionParser() {
    }

    static BuildCacheConfig buildCache(TomlTable table, Path configPath) {
        if (table == null) {
            return BuildCacheConfig.disabled();
        }
        validateKeys("buildCache", table, BUILD_CACHE_KEYS);
        if (!booleanOrDefault(table, "buildCache", "enabled", false)) {
            return BuildCacheConfig.disabled();
        }
        String rawDir = stringOrNull(table, "buildCache", "dir");
        Path directory = rawDir != null
                ? resolveConfigRelativePath(rawDir, configPath)
                : UserGlobalDirectory.buildCache();
        int maxSizeMb = positiveIntOrDefault(table, "buildCache", "maxSizeMb", DEFAULT_BUILD_CACHE_MAX_SIZE_MB);
        return new BuildCacheConfig(
                true, Optional.of(directory), (long) maxSizeMb * 1024L * 1024L, buildCacheRemote(table));
    }

    static Map<String, RepositoryCredentialSettings> repositoryCredentials(TomlTable table) {
        if (table == null) {
            return Map.of();
        }
        Map<String, RepositoryCredentialSettings> credentials = new LinkedHashMap<>();
        for (String id : table.keySet()) {
            TomlTable credentialTable = table.getTable(List.of(id));
            if (credentialTable == null) {
                throw new UserGlobalConfigException(
                        "Invalid value for [repositoryCredentials]." + id
                                + " in user global config. Use a table with usernameEnv and passwordEnv, or tokenEnv.");
            }
            validateKeys("repositoryCredentials." + id, credentialTable, CREDENTIAL_KEYS);
            credentials.put(id, credential(id, credentialTable));
        }
        return credentials;
    }

    static void validateRemoteCredentialReference(
            BuildCacheConfig buildCache,
            Map<String, RepositoryCredentialSettings> credentials) {
        buildCache.remote().flatMap(RemoteBuildCacheConfig::credentials).ifPresent(id -> {
            if (!credentials.containsKey(id)) {
                throw new UserGlobalConfigException(
                        "[buildCache.remote] references undefined credential `" + id
                                + "` in user global config. Define [repositoryCredentials." + id + "].");
            }
        });
    }

    private static Optional<RemoteBuildCacheConfig> buildCacheRemote(TomlTable buildCacheTable) {
        TomlTable table = buildCacheTable.getTable(List.of("remote"));
        if (table == null) {
            return Optional.empty();
        }
        validateKeys("buildCache.remote", table, BUILD_CACHE_REMOTE_KEYS);
        String url = stringOrNull(table, "buildCache.remote", "url");
        if (url == null) {
            throw new UserGlobalConfigException(
                    "Missing required url in [buildCache.remote] in user global config. Add `url = \"https://...\"`.");
        }
        Optional<String> credentials = Optional.ofNullable(stringOrNull(table, "buildCache.remote", "credentials"));
        boolean push = booleanOrDefault(table, "buildCache.remote", "push", false);
        return Optional.of(new RemoteBuildCacheConfig(url, credentials, push));
    }

    private static RepositoryCredentialSettings credential(String id, TomlTable table) {
        String section = "repositoryCredentials." + id;
        String tokenEnv = stringOrNull(table, section, "tokenEnv");
        String usernameEnv = stringOrNull(table, section, "usernameEnv");
        String passwordEnv = stringOrNull(table, section, "passwordEnv");
        if (tokenEnv != null) {
            if (usernameEnv != null || passwordEnv != null) {
                throw new UserGlobalConfigException(
                        "[" + section + "] sets tokenEnv together with usernameEnv/passwordEnv in user global config. "
                                + "Use tokenEnv for bearer auth, or usernameEnv and passwordEnv for basic auth, not both.");
            }
            return RepositoryCredentialSettings.token(id, tokenEnv);
        }
        if (usernameEnv == null || passwordEnv == null) {
            throw new UserGlobalConfigException(
                    "[" + section + "] must set tokenEnv, or both usernameEnv and passwordEnv, in user global config.");
        }
        return RepositoryCredentialSettings.basic(id, usernameEnv, passwordEnv);
    }
}
