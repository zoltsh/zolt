package sh.zolt.manifest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authored project-local test source, runtime, integration, and suite settings. */
public record AuthoredTests(
        Optional<Sources> sources,
        Optional<AuthoredTestRuntime> runtime,
        Optional<Integration> integration,
        Map<LocalId, AuthoredTestSuite> suites) {
    private static final LocalId RESERVED_ALL = new LocalId("all");

    public AuthoredTests {
        sources = Objects.requireNonNull(sources, "Authored test sources must not be null.");
        runtime = Objects.requireNonNull(runtime, "Authored test runtime must not be null.");
        integration = Objects.requireNonNull(
                integration, "Authored integration tests must not be null.");
        suites = immutableSuites(suites);
    }

    public static AuthoredTests empty() {
        return new AuthoredTests(Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    /** Custom Java and Groovy unit-test source roots. */
    public record Sources(
            List<ManifestRelativePath> java,
            List<ManifestRelativePath> groovy) {
        public Sources {
            java = ManifestModelValues.sortedDistinctList(java, "Java test source roots");
            groovy = ManifestModelValues.sortedDistinctList(groovy, "Groovy test source roots");
            if (java.isEmpty() && groovy.isEmpty()) {
                throw new IllegalArgumentException("Authored test sources must not be empty.");
            }
        }
    }

    /** Custom integration-test roots; output remains owned by {@code [build.output]}. */
    public record Integration(
            List<ManifestRelativePath> sources,
            List<ManifestRelativePath> resources) {
        public Integration {
            sources = ManifestModelValues.sortedDistinctList(
                    sources, "Integration-test source roots");
            resources = ManifestModelValues.sortedDistinctList(
                    resources, "Integration-test resource roots");
            if (sources.isEmpty() && resources.isEmpty()) {
                throw new IllegalArgumentException("Authored integration tests must not be empty.");
            }
        }
    }

    private static Map<LocalId, AuthoredTestSuite> immutableSuites(
            Map<LocalId, AuthoredTestSuite> values) {
        Map<LocalId, AuthoredTestSuite> copy = ManifestModelValues.immutableSortedMap(
                values,
                Comparator.naturalOrder(),
                "Test suite ID",
                "Authored test suite");
        if (copy.containsKey(RESERVED_ALL)) {
            throw new IllegalArgumentException("`all` is reserved and cannot be a test suite ID.");
        }
        return copy;
    }
}
