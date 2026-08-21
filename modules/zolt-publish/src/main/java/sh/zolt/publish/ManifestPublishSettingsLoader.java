package sh.zolt.publish;

import java.nio.file.Path;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/**
 * Reads one manifest's {@code [publish]} domain in the final language.
 *
 * <p>The final-language twin of {@link PublishSettingsReader}. Publication settings are
 * project-local (design §4.5), so the authored document is read without workspace composition; that
 * also lets a sparse workspace member manifest be a valid input. Credential references are validated
 * during effective composition (design §14.2), not here.
 */
public final class ManifestPublishSettingsLoader {
    private final ManifestProjectConfigLoader manifestLoader;

    public ManifestPublishSettingsLoader() {
        this(new ManifestProjectConfigLoader());
    }

    public ManifestPublishSettingsLoader(ManifestProjectConfigLoader manifestLoader) {
        this.manifestLoader = manifestLoader;
    }

    /** Reads the publication settings authored in the manifest at {@code manifestPath}. */
    public PublishSettings read(Path manifestPath) {
        return ManifestPublishSettingsAdapter.adapt(
                manifestLoader.document(manifestPath).authored().publishing());
    }
}
