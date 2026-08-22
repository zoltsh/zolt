package sh.zolt.manifest.authored;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryCredential;

/** Immutable authored shared credentials containing environment names but never secrets. */
public record AuthoredCredentials(Map<LocalId, RepositoryCredential> entries) {
    public AuthoredCredentials {
        Objects.requireNonNull(entries, "Authored credentials must not be null.");
        TreeMap<LocalId, RepositoryCredential> copy = new TreeMap<>();
        entries.forEach((id, credential) -> copy.put(
                Objects.requireNonNull(id, "Credential ID must not be null."),
                Objects.requireNonNull(credential, "Credential must not be null.")));
        validateEnvironmentVariableCase(copy);
        entries = Collections.unmodifiableMap(copy);
    }

    public static AuthoredCredentials empty() {
        return new AuthoredCredentials(Map.of());
    }

    private static void validateEnvironmentVariableCase(Map<LocalId, RepositoryCredential> credentials) {
        Map<String, EnvironmentVariableName> spellingByFoldedName = new HashMap<>();
        credentials.values().forEach(credential -> {
            switch (credential) {
                case RepositoryCredential.BearerToken bearer ->
                    validateEnvironmentVariableCase(bearer.tokenEnvironment(), spellingByFoldedName);
                case RepositoryCredential.Basic basic -> {
                    validateEnvironmentVariableCase(basic.usernameEnvironment(), spellingByFoldedName);
                    validateEnvironmentVariableCase(basic.passwordEnvironment(), spellingByFoldedName);
                }
            }
        });
    }

    private static void validateEnvironmentVariableCase(
            EnvironmentVariableName name,
            Map<String, EnvironmentVariableName> spellingByFoldedName) {
        String folded = asciiLowercase(name.value());
        EnvironmentVariableName existing = spellingByFoldedName.putIfAbsent(folded, name);
        if (existing != null && !existing.equals(name)) {
            throw new IllegalArgumentException(
                    "Environment-variable names `" + existing + "` and `" + name
                            + "` differ only by ASCII case.");
        }
    }

    private static String asciiLowercase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append(character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character);
        }
        return result.toString();
    }
}
