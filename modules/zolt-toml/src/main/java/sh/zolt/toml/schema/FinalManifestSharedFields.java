package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for versions, repositories, credentials, and platforms. */
public final class FinalManifestSharedFields {
    public static final ManifestField VERSIONS_ENTRY = mutableMapEntry(
            FinalManifestPaths.VERSIONS, "<id>", ManifestValueKind.STRING, 4_010);
    public static final ManifestField REPOSITORIES_CENTRAL = field(
            FinalManifestPaths.REPOSITORIES,
            "central",
            ManifestValueKind.BOOLEAN_OR_STRING_OR_INLINE_TABLE,
            4_110);
    public static final ManifestField REPOSITORIES_ORDER = field(
            FinalManifestPaths.REPOSITORIES, "order", ManifestValueKind.STRING_ARRAY, 4_120);
    public static final ManifestField REPOSITORY_URL = field(
            FinalManifestPaths.REPOSITORY, "url", ManifestValueKind.STRING, 4_210);
    public static final ManifestField REPOSITORY_CREDENTIALS = field(
            FinalManifestPaths.REPOSITORY, "credentials", ManifestValueKind.STRING, 4_220);
    public static final ManifestField CREDENTIAL_TOKEN_ENV = field(
            FinalManifestPaths.CREDENTIAL, "tokenEnv", ManifestValueKind.STRING, 4_310);
    public static final ManifestField CREDENTIAL_USERNAME_ENV = field(
            FinalManifestPaths.CREDENTIAL, "usernameEnv", ManifestValueKind.STRING, 4_320);
    public static final ManifestField CREDENTIAL_PASSWORD_ENV = field(
            FinalManifestPaths.CREDENTIAL, "passwordEnv", ManifestValueKind.STRING, 4_330);
    public static final ManifestField PLATFORMS_ENTRY = mutableMapEntry(
            FinalManifestPaths.PLATFORMS,
            "<coordinate>",
            ManifestValueKind.STRING_OR_INLINE_TABLE,
            4_410);

    private FinalManifestSharedFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                VERSIONS_ENTRY,
                REPOSITORIES_CENTRAL,
                REPOSITORIES_ORDER,
                REPOSITORY_URL,
                REPOSITORY_CREDENTIALS,
                CREDENTIAL_TOKEN_ENV,
                CREDENTIAL_USERNAME_ENV,
                CREDENTIAL_PASSWORD_ENV,
                PLATFORMS_ENTRY);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }

    private static ManifestField mutableMapEntry(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.mutableMapEntry(section, name, kind, canonicalOrder);
    }
}
