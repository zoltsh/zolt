package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for tasks and built-in command aliases. */
public final class FinalManifestCommandFields {
    public static final ManifestField TASK_DESCRIPTION = field(
            FinalManifestPaths.TASK, "description", ManifestValueKind.STRING, 9_001);
    public static final ManifestField TASK_RUN = field(
            FinalManifestPaths.TASK, "run", ManifestValueKind.STRING_ARRAY, 9_002);
    public static final ManifestField TASK_CWD = field(
            FinalManifestPaths.TASK, "cwd", ManifestValueKind.STRING, 9_003);
    public static final ManifestField TASK_ENV = field(
            FinalManifestPaths.TASK, "env", ManifestValueKind.INLINE_TABLE, 9_004);
    public static final ManifestField ALIASES_ENTRY = field(
            FinalManifestPaths.ALIASES, "<id>", ManifestValueKind.STRING_ARRAY, 9_101);

    private FinalManifestCommandFields() {
    }

    static List<ManifestField> fields() {
        return List.of(TASK_DESCRIPTION, TASK_RUN, TASK_CWD, TASK_ENV, ALIASES_ENTRY);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
