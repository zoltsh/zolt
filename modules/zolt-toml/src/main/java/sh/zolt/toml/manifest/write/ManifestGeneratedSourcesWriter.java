package sh.zolt.toml.manifest.write;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;

/** Emits the complete canonical authored generated-source domain. */
final class ManifestGeneratedSourcesWriter {
    private final ManifestGeneratedToolsPresetsWriter declarations =
            new ManifestGeneratedToolsPresetsWriter();
    private final ManifestGeneratedStepsWriter steps = new ManifestGeneratedStepsWriter();

    void write(
            ManifestTomlEmitter emitter,
            Optional<AuthoredGeneratedSources> generated,
            ManifestRelativePath buildOutputRoot) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        ManifestRelativePath outputRoot = Objects.requireNonNull(
                buildOutputRoot, "Build output root is required.");
        Objects.requireNonNull(generated, "Authored generated sources are required.")
                .ifPresent(value -> {
                    declarations.write(emitter, value.tools(), value.presets());
                    steps.write(emitter, value.main(), value.test(), outputRoot);
                });
    }
}
