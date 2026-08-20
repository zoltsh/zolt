package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored build roots, outputs, and metadata. */
public final class FinalManifestBuildFields {
    public static final ManifestField BUILD_SOURCES = field(
            FinalManifestPaths.BUILD, "sources", ManifestValueKind.STRING_ARRAY, 6_001);
    public static final ManifestField BUILD_OUTPUT_ROOT = field(
            FinalManifestPaths.BUILD_OUTPUT, "root", ManifestValueKind.STRING, 6_011);
    public static final ManifestField BUILD_OUTPUT_MAIN = field(
            FinalManifestPaths.BUILD_OUTPUT, "main", ManifestValueKind.STRING, 6_012);
    public static final ManifestField BUILD_OUTPUT_TEST = field(
            FinalManifestPaths.BUILD_OUTPUT, "test", ManifestValueKind.STRING, 6_013);
    public static final ManifestField BUILD_OUTPUT_INTEGRATION = field(
            FinalManifestPaths.BUILD_OUTPUT, "integration", ManifestValueKind.STRING, 6_014);
    public static final ManifestField BUILD_METADATA_BUILD_INFO = field(
            FinalManifestPaths.BUILD_METADATA, "buildInfo", ManifestValueKind.BOOLEAN, 6_021);
    public static final ManifestField BUILD_METADATA_GIT = field(
            FinalManifestPaths.BUILD_METADATA, "git", ManifestValueKind.BOOLEAN, 6_022);
    public static final ManifestField BUILD_METADATA_REPRODUCIBLE = field(
            FinalManifestPaths.BUILD_METADATA, "reproducible", ManifestValueKind.BOOLEAN, 6_023);

    private FinalManifestBuildFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                BUILD_SOURCES,
                BUILD_OUTPUT_ROOT,
                BUILD_OUTPUT_MAIN,
                BUILD_OUTPUT_TEST,
                BUILD_OUTPUT_INTEGRATION,
                BUILD_METADATA_BUILD_INFO,
                BUILD_METADATA_GIT,
                BUILD_METADATA_REPRODUCIBLE);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
