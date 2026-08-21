package sh.zolt.toml.manifest;

import sh.zolt.project.CoverageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;

/**
 * The single place the final-language equivalence tests touch the legacy dialect.
 *
 * <p>When {@link ZoltTomlParser} is deleted in the cleanup phase, delete this helper and re-point
 * {@link ManifestProjectConfigEquivalenceTest} at the golden canonical fixtures; every other line of
 * those tests already describes the final language only.
 */
final class LegacyManifestDialect {
    private static final ZoltTomlParser PARSER = new ZoltTomlParser();

    private LegacyManifestDialect() {
    }

    /** Parses one legacy-dialect manifest into the legacy {@link ProjectConfig}. */
    static ProjectConfig parse(String legacySource) {
        return PARSER.parse(legacySource);
    }

    /** Parses the legacy {@code [coverage]} floors. */
    static CoverageSettings coverageFloors(String legacySource) {
        return PARSER.parseCoverageFloors(legacySource);
    }
}
