package sh.zolt.toml;

import sh.zolt.toml.support.TomlValidation;
import sh.zolt.toml.support.TomlScalars;
import sh.zolt.error.ActionableError;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.tomlj.TomlTable;

public final class RepositorySectionCodec {
    private static final Set<String> REPOSITORY_KEYS = Set.of("url", "credentials");
    private static final Set<String> REPOSITORY_CREDENTIAL_KEYS = Set.of("usernameEnv", "passwordEnv", "tokenEnv");

    private RepositorySectionCodec() {
    }

    public static Map<String, RepositorySettings> repositorySettings(TomlTable table) {
        if (table == null) {
            return Map.of();
        }

        Map<String, RepositorySettings> values = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object rawValue = table.get(List.of(key));
            if (rawValue instanceof String value) {
                if (value.isBlank()) {
                    throw invalidRepositoryValue(key);
                }
                values.put(key, RepositorySettings.unauthenticated(key, value));
                continue;
            }
            if (rawValue instanceof TomlTable repositoryTable) {
                TomlValidation.validateKeys("repositories." + key, repositoryTable, REPOSITORY_KEYS);
                String url = TomlScalars.requiredString(repositoryTable, "repositories." + key, "url");
                Optional<String> credentials =
                        TomlScalars.optionalString(repositoryTable, "repositories." + key, "credentials");
                values.put(key, new RepositorySettings(key, url, credentials));
                continue;
            }
            throw invalidRepositoryValue(key);
        }
        return values;
    }

    public static Map<String, String> repositoryUrls(
            Map<String, RepositorySettings> repositorySettings) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (Map.Entry<String, RepositorySettings> entry : repositorySettings.entrySet()) {
            urls.put(entry.getKey(), entry.getValue().url());
        }
        return urls;
    }

    public static Map<String, RepositoryCredentialSettings> repositoryCredentials(
            TomlTable table) {
        if (table == null) {
            return Map.of();
        }

        Map<String, RepositoryCredentialSettings> values = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            TomlTable credentialTable = table.getTable(List.of(key));
            if (credentialTable == null) {
                throw new ZoltConfigException(
                        "Invalid value for [repositoryCredentials]." + key + " in zolt.toml. Use a table with usernameEnv and passwordEnv, or tokenEnv.");
            }
            TomlValidation.validateKeys("repositoryCredentials." + key, credentialTable, REPOSITORY_CREDENTIAL_KEYS);
            values.put(key, credential(key, credentialTable));
        }
        return values;
    }

    private static RepositoryCredentialSettings credential(String key, TomlTable table) {
        String section = "repositoryCredentials." + key;
        Optional<String> tokenEnv = TomlScalars.optionalString(table, section, "tokenEnv");
        Optional<String> usernameEnv = TomlScalars.optionalString(table, section, "usernameEnv");
        Optional<String> passwordEnv = TomlScalars.optionalString(table, section, "passwordEnv");
        if (tokenEnv.isPresent()) {
            if (usernameEnv.isPresent() || passwordEnv.isPresent()) {
                throw new ZoltConfigException(ActionableError.of(
                        "Repository credential `" + key + "` sets tokenEnv together with usernameEnv or passwordEnv in zolt.toml.",
                        "Use tokenEnv for bearer/PAT authentication or usernameEnv with passwordEnv for basic authentication, not both."));
            }
            return RepositoryCredentialSettings.token(key, tokenEnv.orElseThrow());
        }
        if (usernameEnv.isEmpty() || passwordEnv.isEmpty()) {
            throw new ZoltConfigException(ActionableError.of(
                    "Repository credential `" + key + "` is missing credential fields in zolt.toml.",
                    "Set tokenEnv for bearer/PAT authentication, or both usernameEnv and passwordEnv for basic authentication."));
        }
        return RepositoryCredentialSettings.basic(key, usernameEnv.orElseThrow(), passwordEnv.orElseThrow());
    }

    public static void validateRepositoryCredentialReferences(
            Map<String, RepositorySettings> repositories,
            Map<String, RepositoryCredentialSettings> credentials) {
        for (RepositorySettings repository : repositories.values()) {
            Optional<String> credentialId = repository.credentials();
            if (credentialId.isPresent() && !credentials.containsKey(credentialId.orElseThrow())) {
                throw new ZoltConfigException(
                        "Repository `"
                                + repository.id()
                                + "` references credentials `"
                                + credentialId.orElseThrow()
                                + "`, but [repositoryCredentials."
                                + credentialId.orElseThrow()
                                + "] is not defined.");
            }
        }
    }

    static void writeRepositories(StringBuilder toml, Map<String, RepositorySettings> repositories) {
        toml.append("[repositories]\n");
        for (Map.Entry<String, RepositorySettings> entry : new TreeMap<>(repositories).entrySet()) {
            RepositorySettings repository = entry.getValue();
            toml.append(quote(entry.getKey())).append(" = ");
            if (repository.credentials().isEmpty()) {
                toml.append(quote(repository.url())).append('\n');
                continue;
            }
            toml.append("{ url = ")
                    .append(quote(repository.url()))
                    .append(", credentials = ")
                    .append(quote(repository.credentials().orElseThrow()))
                    .append(" }\n");
        }
        toml.append('\n');
    }

    static void writeRepositoryCredentials(
            StringBuilder toml,
            Map<String, RepositoryCredentialSettings> credentials) {
        for (RepositoryCredentialSettings credential : new TreeMap<>(credentials).values()) {
            toml.append("[repositoryCredentials.")
                    .append(quote(credential.id()))
                    .append("]\n");
            if (credential.usesToken()) {
                writeAssignment(toml, "tokenEnv", credential.tokenEnv().orElseThrow());
            } else {
                writeAssignment(toml, "usernameEnv", credential.usernameEnv().orElseThrow());
                writeAssignment(toml, "passwordEnv", credential.passwordEnv().orElseThrow());
            }
            toml.append('\n');
        }
    }

    private static ZoltConfigException invalidRepositoryValue(String key) {
        return new ZoltConfigException(
                "Invalid value for [repositories]." + key + " in zolt.toml. Use a non-empty URL string or { url = \"...\", credentials = \"...\" }.");
    }

    private static void writeAssignment(StringBuilder toml, String key, String value) {
        toml.append(key).append(" = ").append(quote(value)).append('\n');
    }

    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
