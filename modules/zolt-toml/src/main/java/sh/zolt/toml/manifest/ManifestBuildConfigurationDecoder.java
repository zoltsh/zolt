package sh.zolt.toml.manifest;

import java.util.Objects;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;

/** Composes the complete authored build configuration without applying defaults. */
final class ManifestBuildConfigurationDecoder {
    private final ManifestBuildDecoder buildDecoder = new ManifestBuildDecoder();
    private final ManifestCompilerDecoder compilerDecoder = new ManifestCompilerDecoder();
    private final ManifestResourcesDecoder resourcesDecoder = new ManifestResourcesDecoder();
    private final ManifestTestsDecoder testsDecoder = new ManifestTestsDecoder();
    private final ManifestCoverageDecoder coverageDecoder = new ManifestCoverageDecoder();

    AuthoredBuildConfiguration decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return new AuthoredBuildConfiguration(
                buildDecoder.decode(index),
                compilerDecoder.decode(index),
                resourcesDecoder.decode(index),
                testsDecoder.decode(index),
                coverageDecoder.decode(index));
    }
}
