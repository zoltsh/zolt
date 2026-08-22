package sh.zolt.toml.manifest.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import sh.zolt.project.ProjectConfig;

/**
 * Shared plumbing for the final-language boundary tests: one loader and one golden-fixture reader.
 */
final class FinalManifests {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();
    private static final String GOLDEN_ROOT = "/golden/manifest-language/";

    private FinalManifests() {
    }

    static ManifestProjectConfigLoader loader() {
        return LOADER;
    }

    /** Loads one final-language manifest through the project boundary. */
    static ProjectConfig load(String finalSource) {
        return LOADER.load(finalSource);
    }

    /** Loads one canonical golden manifest through the final boundary. */
    static ProjectConfig golden(String resourceName) throws IOException {
        return LOADER.load(goldenSource(resourceName));
    }

    /** The exact bytes of one canonical golden manifest. */
    static String goldenSource(String resourceName) throws IOException {
        try (InputStream stream =
                FinalManifests.class.getResourceAsStream(GOLDEN_ROOT + resourceName)) {
            return new String(
                    Objects.requireNonNull(stream, resourceName).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }
}
