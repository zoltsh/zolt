package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.manifest.authored.AuthoredTests;

/** Composes authored build and generated-source domains in canonical schema order. */
final class ManifestBuildConfigurationDecoder {
    private final ManifestBuildDecoder buildDecoder = new ManifestBuildDecoder();
    private final ManifestCompilerDecoder compilerDecoder = new ManifestCompilerDecoder();
    private final ManifestResourcesDecoder resourcesDecoder = new ManifestResourcesDecoder();
    private final ManifestGeneratedSourcesDecoder generatedDecoder =
            new ManifestGeneratedSourcesDecoder();
    private final ManifestTestsDecoder testsDecoder = new ManifestTestsDecoder();
    private final ManifestCoverageDecoder coverageDecoder = new ManifestCoverageDecoder();

    Decoded decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredBuild> build = buildDecoder.decode(index);
        Optional<AuthoredCompiler> compiler = compilerDecoder.decode(index);
        Optional<AuthoredResources> resources = resourcesDecoder.decode(index);
        Optional<AuthoredGeneratedSources> generated = generatedDecoder.decode(index);
        Optional<AuthoredTests> tests = testsDecoder.decode(index);
        Optional<AuthoredCoverage> coverage = coverageDecoder.decode(index);
        AuthoredBuildConfiguration configuration = new AuthoredBuildConfiguration(
                build, compiler, resources, tests, coverage);
        return new Decoded(configuration, generated);
    }

    record Decoded(
            AuthoredBuildConfiguration build,
            Optional<AuthoredGeneratedSources> generated) {
        Decoded {
            Objects.requireNonNull(build, "Decoded build configuration must not be null.");
            generated = Objects.requireNonNull(
                    generated, "Decoded generated sources must not be null.");
        }
    }
}
