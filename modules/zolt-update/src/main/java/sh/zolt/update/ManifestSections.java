package sh.zolt.update;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.LocalId;

/**
 * Canonical final-language table names used as update-target section identities.
 *
 * <p>A section is part of a target's opaque identity (design §20.1), so these names are the exact
 * canonical table headers of the final manifest language and never a display convenience.
 */
final class ManifestSections {
    static final String VERSIONS = "[versions]";
    static final String PLATFORMS = "[platforms]";
    static final String DEPENDENCY_CONSTRAINTS = "[dependencies.constraints]";
    static final String BOM_VERSIONS = "[bom.versions]";
    static final String BOM_IMPORTS = "[bom.imports]";

    private ManifestSections() {
    }

    /** The canonical dependency table that owns {@code lane}. */
    static String lane(DependencyLane lane) {
        return switch (lane) {
            case IMPLEMENTATION -> "[dependencies]";
            case API -> "[dependencies.api]";
            case RUNTIME -> "[dependencies.runtime]";
            case PROVIDED -> "[dependencies.provided]";
            case DEV -> "[dependencies.dev]";
            case TEST -> "[dependencies.test]";
            case PROCESSOR -> "[dependencies.processor]";
            case TEST_PROCESSOR -> "[dependencies.test-processor]";
        };
    }

    /** The lane owned by a canonical dependency table produced by {@link #lane(DependencyLane)}. */
    static DependencyLane laneOf(String section) {
        return switch (section) {
            case "[dependencies]" -> DependencyLane.IMPLEMENTATION;
            case "[dependencies.api]" -> DependencyLane.API;
            case "[dependencies.runtime]" -> DependencyLane.RUNTIME;
            case "[dependencies.provided]" -> DependencyLane.PROVIDED;
            case "[dependencies.dev]" -> DependencyLane.DEV;
            case "[dependencies.test]" -> DependencyLane.TEST;
            case "[dependencies.processor]" -> DependencyLane.PROCESSOR;
            case "[dependencies.test-processor]" -> DependencyLane.TEST_PROCESSOR;
            default -> throw new IllegalStateException("Unmapped dependency section: " + section);
        };
    }

    /** The canonical declaration table of one named generated tool. */
    static String generatedTool(LocalId id) {
        return "[generated.tools." + id.value() + "]";
    }
}
