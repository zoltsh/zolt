package sh.zolt.project;

import java.util.List;

public record DependencyMetadata(
        String section,
        String coordinate,
        String version,
        String versionRef,
        boolean managed,
        String workspace,
        boolean optional,
        boolean publishOnly,
        List<DependencyExclusionSpec> exclusions,
        String classifier,
        String type) {
    public DependencyMetadata {
        section = normalize(section);
        coordinate = normalize(coordinate);
        version = version == null || version.isBlank() ? null : version;
        versionRef = versionRef == null || versionRef.isBlank() ? null : versionRef;
        workspace = workspace == null || workspace.isBlank() ? null : workspace;
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        classifier = classifier == null || classifier.isBlank() ? null : classifier;
        type = type == null || type.isBlank() ? null : type;
    }

    public DependencyMetadata(
            String section,
            String coordinate,
            String version,
            String versionRef,
            boolean managed,
            String workspace,
            boolean optional,
            boolean publishOnly,
            List<DependencyExclusionSpec> exclusions) {
        this(section, coordinate, version, versionRef, managed, workspace, optional, publishOnly, exclusions, null, null);
    }

    public DependencyMetadata(
            String section,
            String coordinate,
            String version,
            boolean managed,
            String workspace,
            boolean optional,
            boolean publishOnly,
            List<DependencyExclusionSpec> exclusions) {
        this(section, coordinate, version, null, managed, workspace, optional, publishOnly, exclusions);
    }

    public static String key(String section, String coordinate) {
        return section + "|" + coordinate;
    }

    /** The final manifest section that declares this dependency (design §8.1). */
    public String manifestSection() {
        return manifestSection(section);
    }

    /**
     * The final manifest section for one engine metadata key. The engine keys dependency metadata by
     * the pre-cut section spelling so lock identity stays stable; every diagnostic that names a
     * section to an author must translate through here.
     */
    public static String manifestSection(String section) {
        return switch (section) {
            case "api.dependencies" -> "dependencies.api";
            case "runtime.dependencies" -> "dependencies.runtime";
            case "provided.dependencies" -> "dependencies.provided";
            case "dev.dependencies" -> "dependencies.dev";
            case "test.dependencies" -> "dependencies.test";
            case "annotationProcessors" -> "dependencies.processor";
            case "test.annotationProcessors" -> "dependencies.test-processor";
            default -> section;
        };
    }

    public boolean emptyMetadata() {
        return versionRef == null
                && !optional
                && !publishOnly
                && exclusions.isEmpty()
                && classifier == null
                && type == null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Dependency metadata section and coordinate are required.");
        }
        return value;
    }
}
