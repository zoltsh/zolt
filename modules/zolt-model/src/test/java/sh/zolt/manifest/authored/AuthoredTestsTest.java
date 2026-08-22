package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.TestClassPattern;

final class AuthoredTestsTest {
    @Test
    void storesDistinctAuthoredOrderRootsAndSortedNamedSuites() {
        AuthoredTests.Sources sources = new AuthoredTests.Sources(
                List.of(path("src/z-test/java"), path("src/a-test/java")),
                List.of(path("src/test/groovy")));
        AuthoredTests.Integration integration = new AuthoredTests.Integration(
                List.of(path("src/integration-test/java")),
                List.of(path("src/integration-test/resources")));
        LinkedHashMap<LocalId, AuthoredTestSuite> suites = new LinkedHashMap<>();
        suites.put(new LocalId("smoke"), suite("*SmokeTest"));
        suites.put(new LocalId("fast"), suite("*FastTest"));

        AuthoredTests tests = new AuthoredTests(
                Optional.of(sources), Optional.empty(), Optional.of(integration), suites);
        suites.clear();

        assertEquals(
                List.of(path("src/z-test/java"), path("src/a-test/java")),
                tests.sources().orElseThrow().java(),
                "test source roots are order-bearing and keep authored order");
        assertEquals(
                List.of("fast", "smoke"),
                tests.suites().keySet().stream().map(LocalId::value).toList());
        assertThrows(UnsupportedOperationException.class, () -> tests.suites().clear());
    }

    @Test
    void rejectsEmptySourceTablesDuplicateRootsAndReservedAllSuite() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTests.Sources(
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTests.Integration(
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTests.Sources(
                List.of(path("src/test/java"), path("src/test/java")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTests(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(new LocalId("all"), suite("*Test"))));
    }

    @Test
    void emptyAuthoredTestDomainDoesNotMaterializeConventionalDefaults() {
        assertEquals(Map.of(), AuthoredTests.empty().suites());
        assertEquals(Optional.empty(), AuthoredTests.empty().sources());
    }

    private static AuthoredTestSuite suite(String pattern) {
        return new AuthoredTestSuite(
                List.of(new TestClassPattern(pattern)),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of());
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }
}
