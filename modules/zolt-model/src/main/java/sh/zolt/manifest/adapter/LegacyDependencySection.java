package sh.zolt.manifest.adapter;

import sh.zolt.dependency.DependencyLane;

/**
 * The legacy {@code zolt.toml} section name that owned each final {@link DependencyLane}.
 *
 * <p>Legacy {@link sh.zolt.project.DependencyMetadata} keys are {@code section|coordinate} pairs, so
 * the adapter must reproduce the exact section spelling the legacy codec used even though the final
 * language spells the same lane as a {@code [dependencies.*]} sub-table.
 */
public final class LegacyDependencySection {
    private LegacyDependencySection() {
    }

    /** The legacy section name that carried {@code lane}. */
    public static String of(DependencyLane lane) {
        return switch (lane) {
            case API -> "api.dependencies";
            case IMPLEMENTATION -> "dependencies";
            case RUNTIME -> "runtime.dependencies";
            case PROVIDED -> "provided.dependencies";
            case DEV -> "dev.dependencies";
            case TEST -> "test.dependencies";
            case PROCESSOR -> "annotationProcessors";
            case TEST_PROCESSOR -> "test.annotationProcessors";
        };
    }
}
