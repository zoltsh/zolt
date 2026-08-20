package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for workspace and project identity metadata. */
public final class FinalManifestIdentityFields {
    public static final ManifestField WORKSPACE_NAME = field(
            FinalManifestPaths.WORKSPACE, "name", ManifestValueKind.STRING, 1_010);
    public static final ManifestField WORKSPACE_MEMBERS_DEFAULT = field(
            FinalManifestPaths.WORKSPACE_MEMBERS, "default", ManifestValueKind.STRING_ARRAY, 1_110);
    public static final ManifestField WORKSPACE_MEMBERS_INCLUDE = field(
            FinalManifestPaths.WORKSPACE_MEMBERS, "include", ManifestValueKind.STRING_ARRAY, 1_120);
    public static final ManifestField WORKSPACE_MEMBERS_EXCLUDE = field(
            FinalManifestPaths.WORKSPACE_MEMBERS, "exclude", ManifestValueKind.STRING_ARRAY, 1_130);
    public static final ManifestField WORKSPACE_PROJECT_GROUP = field(
            FinalManifestPaths.WORKSPACE_PROJECT, "group", ManifestValueKind.STRING, 1_210);
    public static final ManifestField WORKSPACE_PROJECT_VERSION = field(
            FinalManifestPaths.WORKSPACE_PROJECT, "version", ManifestValueKind.STRING, 1_220);
    public static final ManifestField WORKSPACE_PROJECT_JAVA = field(
            FinalManifestPaths.WORKSPACE_PROJECT, "java", ManifestValueKind.INTEGER, 1_230);
    public static final ManifestField WORKSPACE_PROJECT_LICENSE = oneLineField(
            FinalManifestPaths.WORKSPACE_PROJECT,
            "license",
            ManifestValueKind.STRING_OR_INLINE_TABLE,
            1_240);
    public static final ManifestField PROJECT_NAME = field(
            FinalManifestPaths.PROJECT, "name", ManifestValueKind.STRING, 2_010);
    public static final ManifestField PROJECT_VERSION = field(
            FinalManifestPaths.PROJECT, "version", ManifestValueKind.STRING, 2_020);
    public static final ManifestField PROJECT_GROUP = field(
            FinalManifestPaths.PROJECT, "group", ManifestValueKind.STRING, 2_030);
    public static final ManifestField PROJECT_JAVA = field(
            FinalManifestPaths.PROJECT, "java", ManifestValueKind.INTEGER, 2_040);
    public static final ManifestField PROJECT_MAIN = field(
            FinalManifestPaths.PROJECT, "main", ManifestValueKind.STRING, 2_050);
    public static final ManifestField PROJECT_DESCRIPTION = field(
            FinalManifestPaths.PROJECT, "description", ManifestValueKind.STRING, 2_060);
    public static final ManifestField PROJECT_URL = field(
            FinalManifestPaths.PROJECT, "url", ManifestValueKind.STRING, 2_070);
    public static final ManifestField PROJECT_ISSUES = field(
            FinalManifestPaths.PROJECT, "issues", ManifestValueKind.STRING, 2_080);
    public static final ManifestField PROJECT_LICENSE = oneLineField(
            FinalManifestPaths.PROJECT,
            "license",
            ManifestValueKind.STRING_OR_INLINE_TABLE,
            2_090);
    public static final ManifestField PROJECT_SCM_URL = field(
            FinalManifestPaths.PROJECT_SCM, "url", ManifestValueKind.STRING, 2_110);
    public static final ManifestField PROJECT_SCM_CONNECTION = field(
            FinalManifestPaths.PROJECT_SCM, "connection", ManifestValueKind.STRING, 2_120);
    public static final ManifestField PROJECT_SCM_DEVELOPER_CONNECTION = field(
            FinalManifestPaths.PROJECT_SCM,
            "developerConnection",
            ManifestValueKind.STRING,
            2_130);
    public static final ManifestField PROJECT_SCM_TAG = field(
            FinalManifestPaths.PROJECT_SCM, "tag", ManifestValueKind.STRING, 2_140);
    public static final ManifestField PROJECT_DEVELOPER_NAME = field(
            FinalManifestPaths.PROJECT_DEVELOPER, "name", ManifestValueKind.STRING, 2_210);
    public static final ManifestField PROJECT_DEVELOPER_EMAIL = field(
            FinalManifestPaths.PROJECT_DEVELOPER, "email", ManifestValueKind.STRING, 2_220);
    public static final ManifestField PROJECT_DEVELOPER_ORGANIZATION = field(
            FinalManifestPaths.PROJECT_DEVELOPER,
            "organization",
            ManifestValueKind.STRING,
            2_230);
    public static final ManifestField PROJECT_DEVELOPER_URL = field(
            FinalManifestPaths.PROJECT_DEVELOPER, "url", ManifestValueKind.STRING, 2_240);

    private FinalManifestIdentityFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                WORKSPACE_NAME,
                WORKSPACE_MEMBERS_DEFAULT,
                WORKSPACE_MEMBERS_INCLUDE,
                WORKSPACE_MEMBERS_EXCLUDE,
                WORKSPACE_PROJECT_GROUP,
                WORKSPACE_PROJECT_VERSION,
                WORKSPACE_PROJECT_JAVA,
                WORKSPACE_PROJECT_LICENSE,
                PROJECT_NAME,
                PROJECT_VERSION,
                PROJECT_GROUP,
                PROJECT_JAVA,
                PROJECT_MAIN,
                PROJECT_DESCRIPTION,
                PROJECT_URL,
                PROJECT_ISSUES,
                PROJECT_LICENSE,
                PROJECT_SCM_URL,
                PROJECT_SCM_CONNECTION,
                PROJECT_SCM_DEVELOPER_CONNECTION,
                PROJECT_SCM_TAG,
                PROJECT_DEVELOPER_NAME,
                PROJECT_DEVELOPER_EMAIL,
                PROJECT_DEVELOPER_ORGANIZATION,
                PROJECT_DEVELOPER_URL);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }

    private static ManifestField oneLineField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.oneLineField(section, name, kind, canonicalOrder);
    }
}
