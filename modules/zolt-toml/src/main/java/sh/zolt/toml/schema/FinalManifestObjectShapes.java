package sh.zolt.toml.schema;

import java.util.List;

/** Closed inline-object descriptors used by the initial semantic decoder domains. */
public final class FinalManifestObjectShapes {
    public static final ManifestObjectMember LICENSE_ID = member("id", false, 10);
    public static final ManifestObjectMember LICENSE_NAME = member("name", false, 20);
    public static final ManifestObjectMember LICENSE_URL = member("url", false, 30);
    public static final ManifestObjectShape LICENSE = new ManifestObjectShape(
            List.of(LICENSE_ID, LICENSE_NAME, LICENSE_URL),
            List.of(new ManifestObjectShape.PresenceGroup(
                    ManifestObjectShape.PresenceRule.AT_LEAST_ONE,
                    List.of(LICENSE_ID, LICENSE_NAME))));

    public static final ManifestObjectMember CENTRAL_URL = member("url", true, 10);
    public static final ManifestObjectMember CENTRAL_CREDENTIALS = member("credentials", false, 20);
    public static final ManifestObjectShape CENTRAL_REPLACEMENT = new ManifestObjectShape(
            List.of(CENTRAL_URL, CENTRAL_CREDENTIALS), List.of());

    public static final ManifestObjectMember PLATFORM_VERSION = member("version", false, 10);
    public static final ManifestObjectMember PLATFORM_VERSION_REF = member("versionRef", false, 20);
    public static final ManifestObjectShape PLATFORM_SELECTOR = new ManifestObjectShape(
            List.of(PLATFORM_VERSION, PLATFORM_VERSION_REF),
            List.of(new ManifestObjectShape.PresenceGroup(
                    ManifestObjectShape.PresenceRule.EXACTLY_ONE,
                    List.of(PLATFORM_VERSION, PLATFORM_VERSION_REF))));

    private FinalManifestObjectShapes() {
    }

    private static ManifestObjectMember member(String name, boolean required, int canonicalOrder) {
        return new ManifestObjectMember(name, ManifestValueKind.STRING, required, canonicalOrder);
    }
}
