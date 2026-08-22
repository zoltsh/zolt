package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for publication routes, repositories, signing, and Central. */
public final class FinalManifestPublishingFields {
    public static final ManifestField PUBLISH_RELEASE = field(
            FinalManifestPaths.PUBLISH, "release", ManifestValueKind.STRING, 8_001);
    public static final ManifestField PUBLISH_SNAPSHOT = field(
            FinalManifestPaths.PUBLISH, "snapshot", ManifestValueKind.STRING, 8_002);
    public static final ManifestField PUBLISH_REPOSITORY_URL = field(
            FinalManifestPaths.PUBLISH_REPOSITORY,
            "url",
            ManifestValueKind.STRING,
            8_101);
    public static final ManifestField PUBLISH_REPOSITORY_CREDENTIALS = field(
            FinalManifestPaths.PUBLISH_REPOSITORY,
            "credentials",
            ManifestValueKind.STRING,
            8_102);
    public static final ManifestField PUBLISH_SIGNING_METHOD = field(
            FinalManifestPaths.PUBLISH_SIGNING,
            "method",
            ManifestValueKind.STRING,
            8_201);
    public static final ManifestField PUBLISH_SIGNING_KEY_ID = field(
            FinalManifestPaths.PUBLISH_SIGNING,
            "keyId",
            ManifestValueKind.STRING,
            8_202);
    public static final ManifestField PUBLISH_SIGNING_PASSPHRASE_ENV = field(
            FinalManifestPaths.PUBLISH_SIGNING,
            "passphraseEnv",
            ManifestValueKind.STRING,
            8_203);
    public static final ManifestField PUBLISH_CENTRAL_TOKEN_ENV = field(
            FinalManifestPaths.PUBLISH_CENTRAL,
            "tokenEnv",
            ManifestValueKind.STRING,
            8_301);
    public static final ManifestField PUBLISH_CENTRAL_MODE = field(
            FinalManifestPaths.PUBLISH_CENTRAL,
            "mode",
            ManifestValueKind.STRING,
            8_302);
    public static final ManifestField PUBLISH_CENTRAL_NAME = field(
            FinalManifestPaths.PUBLISH_CENTRAL,
            "name",
            ManifestValueKind.STRING,
            8_303);
    public static final ManifestField PUBLISH_CENTRAL_URL = field(
            FinalManifestPaths.PUBLISH_CENTRAL,
            "url",
            ManifestValueKind.STRING,
            8_304);

    private FinalManifestPublishingFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                PUBLISH_RELEASE,
                PUBLISH_SNAPSHOT,
                PUBLISH_REPOSITORY_URL,
                PUBLISH_REPOSITORY_CREDENTIALS,
                PUBLISH_SIGNING_METHOD,
                PUBLISH_SIGNING_KEY_ID,
                PUBLISH_SIGNING_PASSPHRASE_ENV,
                PUBLISH_CENTRAL_TOKEN_ENV,
                PUBLISH_CENTRAL_MODE,
                PUBLISH_CENTRAL_NAME,
                PUBLISH_CENTRAL_URL);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
