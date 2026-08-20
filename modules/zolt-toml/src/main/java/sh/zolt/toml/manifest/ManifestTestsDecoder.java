package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.manifest.authored.AuthoredTests;

/** Composes authored test roots, runtime, integration, and suites without applying defaults. */
final class ManifestTestsDecoder {
    private final ManifestTestRootsDecoder rootsDecoder = new ManifestTestRootsDecoder();
    private final ManifestTestRuntimeDecoder runtimeDecoder = new ManifestTestRuntimeDecoder();
    private final ManifestTestSuitesDecoder suitesDecoder = new ManifestTestSuitesDecoder();

    Optional<AuthoredTests> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredTests.Sources> sources = rootsDecoder.decodeSources(index);
        Optional<AuthoredTestRuntime> runtime = runtimeDecoder.decode(index);
        Optional<AuthoredTests.Integration> integration = rootsDecoder.decodeIntegration(index);
        Optional<Map<LocalId, AuthoredTestSuite>> suites = suitesDecoder.decode(index);
        if (sources.isEmpty()
                && runtime.isEmpty()
                && integration.isEmpty()
                && suites.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredTests(
                sources,
                runtime,
                integration,
                suites.orElseGet(Map::of)));
    }
}
