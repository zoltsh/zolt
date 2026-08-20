package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored main, test, and generated compiler settings. */
public final class FinalManifestCompilerFields {
    public static final ManifestField COMPILER_ENCODING = field(
            FinalManifestPaths.COMPILER, "encoding", ManifestValueKind.STRING, 6_101);
    public static final ManifestField COMPILER_JDK_API = field(
            FinalManifestPaths.COMPILER, "jdkApi", ManifestValueKind.STRING, 6_102);
    public static final ManifestField COMPILER_ARGS = field(
            FinalManifestPaths.COMPILER, "args", ManifestValueKind.STRING_ARRAY, 6_103);
    public static final ManifestField COMPILER_TEST_JDK_API = field(
            FinalManifestPaths.COMPILER_TEST, "jdkApi", ManifestValueKind.STRING, 6_111);
    public static final ManifestField COMPILER_TEST_ARGS = field(
            FinalManifestPaths.COMPILER_TEST, "args", ManifestValueKind.STRING_ARRAY, 6_112);
    public static final ManifestField COMPILER_GENERATED_MAIN = field(
            FinalManifestPaths.COMPILER_GENERATED, "main", ManifestValueKind.STRING, 6_121);
    public static final ManifestField COMPILER_GENERATED_TEST = field(
            FinalManifestPaths.COMPILER_GENERATED, "test", ManifestValueKind.STRING, 6_122);

    private FinalManifestCompilerFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                COMPILER_ENCODING,
                COMPILER_JDK_API,
                COMPILER_ARGS,
                COMPILER_TEST_JDK_API,
                COMPILER_TEST_ARGS,
                COMPILER_GENERATED_MAIN,
                COMPILER_GENERATED_TEST);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
