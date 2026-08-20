package sh.zolt.toml.schema;

import java.util.List;

/** Closed inline-object descriptors for the final manifest schema. */
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

    public static final ManifestObjectMember DEPENDENCY_VERSION = member("version", false, 10);
    public static final ManifestObjectMember DEPENDENCY_VERSION_REF = member("versionRef", false, 20);
    public static final ManifestObjectMember DEPENDENCY_MANAGED =
            member("managed", ManifestValueKind.BOOLEAN, false, 30);
    public static final ManifestObjectMember DEPENDENCY_WORKSPACE =
            member("workspace", ManifestValueKind.BOOLEAN, false, 40);
    public static final ManifestObjectMember DEPENDENCY_OPTIONAL =
            member("optional", ManifestValueKind.BOOLEAN, false, 50);
    public static final ManifestObjectMember DEPENDENCY_PUBLISH_ONLY =
            member("publishOnly", ManifestValueKind.BOOLEAN, false, 60);
    public static final ManifestObjectMember DEPENDENCY_CLASSIFIER = member("classifier", false, 70);
    public static final ManifestObjectMember DEPENDENCY_TYPE = member("type", false, 80);
    public static final ManifestObjectMember DEPENDENCY_EXCLUDE =
            member("exclude", ManifestValueKind.STRING_ARRAY, false, 90);
    public static final ManifestObjectShape DEPENDENCY = new ManifestObjectShape(
            List.of(
                    DEPENDENCY_VERSION,
                    DEPENDENCY_VERSION_REF,
                    DEPENDENCY_MANAGED,
                    DEPENDENCY_WORKSPACE,
                    DEPENDENCY_OPTIONAL,
                    DEPENDENCY_PUBLISH_ONLY,
                    DEPENDENCY_CLASSIFIER,
                    DEPENDENCY_TYPE,
                    DEPENDENCY_EXCLUDE),
            List.of(new ManifestObjectShape.PresenceGroup(
                    ManifestObjectShape.PresenceRule.EXACTLY_ONE,
                    List.of(
                            DEPENDENCY_VERSION,
                            DEPENDENCY_VERSION_REF,
                            DEPENDENCY_MANAGED,
                            DEPENDENCY_WORKSPACE))));

    public static final ManifestObjectMember CONSTRAINT_VERSION = member("version", false, 10);
    public static final ManifestObjectMember CONSTRAINT_VERSION_REF = member("versionRef", false, 20);
    public static final ManifestObjectMember CONSTRAINT_REASON = member("reason", false, 30);
    public static final ManifestObjectShape CONSTRAINT = new ManifestObjectShape(
            List.of(CONSTRAINT_VERSION, CONSTRAINT_VERSION_REF, CONSTRAINT_REASON),
            List.of(new ManifestObjectShape.PresenceGroup(
                    ManifestObjectShape.PresenceRule.EXACTLY_ONE,
                    List.of(CONSTRAINT_VERSION, CONSTRAINT_VERSION_REF))));

    public static final ManifestObjectMember DENY_ENTRY_COORDINATE = member("coordinate", true, 10);
    public static final ManifestObjectMember DENY_ENTRY_REASON = member("reason", false, 20);
    public static final ManifestObjectShape DENY_ENTRY = new ManifestObjectShape(
            List.of(DENY_ENTRY_COORDINATE, DENY_ENTRY_REASON), List.of());

    public static final ManifestObjectMember RESOURCE_TOKEN_PROJECT = member("project", false, 10);
    public static final ManifestObjectMember RESOURCE_TOKEN_ENV = member("env", false, 20);
    public static final ManifestObjectMember RESOURCE_TOKEN_VALUE = member("value", false, 30);
    public static final ManifestObjectShape RESOURCE_TOKEN = new ManifestObjectShape(
            List.of(RESOURCE_TOKEN_PROJECT, RESOURCE_TOKEN_ENV, RESOURCE_TOKEN_VALUE),
            List.of(new ManifestObjectShape.PresenceGroup(
                    ManifestObjectShape.PresenceRule.EXACTLY_ONE,
                    List.of(RESOURCE_TOKEN_PROJECT, RESOURCE_TOKEN_ENV, RESOURCE_TOKEN_VALUE))));

    public static final ManifestObjectMember GENERATED_ARTIFACT_COORDINATE =
            member("coordinate", true, 10);
    public static final ManifestObjectMember GENERATED_ARTIFACT_VERSION =
            member("version", false, 20);
    public static final ManifestObjectMember GENERATED_ARTIFACT_VERSION_REF =
            member("versionRef", false, 30);
    public static final ManifestObjectShape GENERATED_ARTIFACT_REQUEST = new ManifestObjectShape(
            List.of(
                    GENERATED_ARTIFACT_COORDINATE,
                    GENERATED_ARTIFACT_VERSION,
                    GENERATED_ARTIFACT_VERSION_REF),
            List.of(new ManifestObjectShape.PresenceGroup(
                    ManifestObjectShape.PresenceRule.EXACTLY_ONE,
                    List.of(GENERATED_ARTIFACT_VERSION, GENERATED_ARTIFACT_VERSION_REF))));

    private FinalManifestObjectShapes() {
    }

    private static ManifestObjectMember member(String name, boolean required, int canonicalOrder) {
        return member(name, ManifestValueKind.STRING, required, canonicalOrder);
    }

    private static ManifestObjectMember member(
            String name,
            ManifestValueKind valueKind,
            boolean required,
            int canonicalOrder) {
        return new ManifestObjectMember(name, valueKind, required, canonicalOrder);
    }
}
