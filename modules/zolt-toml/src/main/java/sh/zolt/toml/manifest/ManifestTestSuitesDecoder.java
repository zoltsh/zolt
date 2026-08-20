package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Collects authored named test suites while preserving omission and source-order validation. */
final class ManifestTestSuitesDecoder {
    private final ManifestTestSuiteDecoder suiteDecoder = new ManifestTestSuiteDecoder();

    Optional<Map<LocalId, AuthoredTestSuite>> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.SectionEntry> entries =
                index.sectionEntries(FinalManifestPaths.TEST_SUITE);
        if (index.section(FinalManifestPaths.TEST_SUITES).isEmpty() && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<LocalId, AuthoredTestSuite> suites = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry : entries) {
            ManifestTestSuiteDecoder.Decoded decoded = suiteDecoder.decode(index, entry);
            if (suites.put(decoded.id(), decoded.suite()) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate test suite `" + decoded.id() + "`.");
            }
        }
        return Optional.of(ManifestModelValues.immutableSortedMap(
                suites,
                LocalId::compareTo,
                "Test suite ID",
                "Authored test suite"));
    }
}
