package sh.zolt.toml.manifest.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import sh.zolt.project.ProjectConfig;

/**
 * Shared plumbing for the legacy/final manifest pair tests: one loader, one pair assertion, and one
 * golden-fixture reader.
 */
final class FinalManifestPairs {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();
    private static final String GOLDEN_ROOT = "/golden/manifest-language/";

    private FinalManifestPairs() {
    }

    static ManifestProjectConfigLoader loader() {
        return LOADER;
    }

    /** Asserts that both dialects of the same manifest produce the same legacy config. */
    static ProjectConfig assertEquivalent(String legacySource, String finalSource) {
        ProjectConfig legacy = LegacyManifestDialect.parse(legacySource);
        ProjectConfig adapted = LOADER.load(finalSource);
        ProjectConfigEquivalence.assertEquivalent(legacy, adapted);
        return adapted;
    }

    /** Loads one canonical golden manifest through the final boundary. */
    static ProjectConfig golden(String resourceName) throws IOException {
        return LOADER.load(goldenSource(resourceName));
    }

    /** The exact bytes of one canonical golden manifest. */
    static String goldenSource(String resourceName) throws IOException {
        try (InputStream stream =
                FinalManifestPairs.class.getResourceAsStream(GOLDEN_ROOT + resourceName)) {
            return new String(
                    Objects.requireNonNull(stream, resourceName).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }
}
