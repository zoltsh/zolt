package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored coverage floors. */
public final class FinalManifestCoverageFields {
    public static final ManifestField COVERAGE_LINE = field(
            FinalManifestPaths.COVERAGE, "line", ManifestValueKind.NUMBER, 6_910);
    public static final ManifestField COVERAGE_BRANCH = field(
            FinalManifestPaths.COVERAGE, "branch", ManifestValueKind.NUMBER, 6_920);
    public static final ManifestField COVERAGE_INSTRUCTION = field(
            FinalManifestPaths.COVERAGE, "instruction", ManifestValueKind.NUMBER, 6_930);
    public static final ManifestField COVERAGE_METHOD = field(
            FinalManifestPaths.COVERAGE, "method", ManifestValueKind.NUMBER, 6_940);

    private FinalManifestCoverageFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                COVERAGE_LINE,
                COVERAGE_BRANCH,
                COVERAGE_INSTRUCTION,
                COVERAGE_METHOD);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
