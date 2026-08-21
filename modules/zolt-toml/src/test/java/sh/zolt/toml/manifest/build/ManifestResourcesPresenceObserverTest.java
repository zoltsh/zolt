package sh.zolt.toml.manifest.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeResources;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeResourcesWithNullIndex;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeResourcesWithNullObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.toml.ZoltConfigException;

final class ManifestResourcesPresenceObserverTest {
    @Test
    void observesOnlyTheFirstCanonicalImmutableFieldPrefix() {
        ArrayList<AuthoredResources> observed = new ArrayList<>();

        AuthoredResources complete = decodeResources("""
                [resources]
                test = ["src/test/resources"]
                main = ["src/z/resources", "src/a/resources"]

                [resources.filter]
                missing = "keep"
                include = ["**/*.yaml"]
                targets = ["test"]

                [resources.tokens]
                channel = { value = "preview" }
                """, observed::add).orElseThrow();

        assertEquals(1, observed.size());
        AuthoredResources first = observed.getFirst();
        assertEquals(AuthoredResources.empty(), first);
        assertThrows(UnsupportedOperationException.class, () -> first.main().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.tokens().clear());
        assertEquals(
                List.of(
                        new ManifestRelativePath("src/a/resources"),
                        new ManifestRelativePath("src/z/resources")),
                complete.main());
        assertEquals(1, complete.test().size());
        assertTrue(complete.filter().isPresent());
        assertEquals(1, complete.tokens().size());
    }

    @Test
    void retainsCanonicalRootFieldAnchorsAndPreemptsLaterFailures() {
        assertObservedFailure("""
                [resources]
                test = ["src/test/resources", "src/test/resources"]
                main = ["src/main/resources", "src/main/resources"]
                """, "`resources.main`");
        assertObservedFailure("""
                [resources]
                test = ["src/test/resources", "src/test/resources"]
                """, "`resources.test`");
        assertObservedFailure("[resources]\nmain = []\n", "`resources.main`");
    }

    @Test
    void observesTheFirstConstructibleFilterPrefixAtItsCanonicalAnchor() {
        ArrayList<AuthoredResources> observed = new ArrayList<>();
        AuthoredResources complete = decodeResources("""
                [resources.filter]
                missing = "keep"
                include = ["z*.txt", "a*.txt"]
                targets = ["test", "main"]
                """, observed::add).orElseThrow();

        assertEquals(1, observed.size());
        AuthoredResources.Filter first = observed.getFirst().filter().orElseThrow();
        assertEquals(
                List.of(AuthoredResources.Target.MAIN, AuthoredResources.Target.TEST),
                first.targets().orElseThrow());
        assertEquals(List.of("z*.txt"), strings(first));
        assertTrue(first.missing().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.targets().orElseThrow().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.include().clear());
        AuthoredResources.Filter finalFilter = complete.filter().orElseThrow();
        assertEquals(List.of("a*.txt", "z*.txt"), strings(finalFilter));
        assertEquals(
                AuthoredResources.MissingTokenPolicy.KEEP,
                finalFilter.missing().orElseThrow());

        assertObservedFailure("""
                [resources.filter]
                missing = "keep"
                include = ["*.txt", "*.txt"]
                targets = ["test"]
                """, "`resources.filter.targets[0]`");
        assertObservedFailure("""
                [resources.filter]
                include = ["*.txt", "*.txt"]
                """, "`resources.filter.include[0]`");
    }

    @Test
    void leavesUnconstructibleFiltersUnobserved() {
        AtomicInteger observations = new AtomicInteger();

        assertUnobservedFailure("""
                [resources.filter]
                targets = []
                include = ["*.txt"]
                """, observations, "`resources.filter.targets`", true);
        assertUnobservedFailure("""
                [resources.filter]
                targets = ["main"]
                """, observations, "`resources.filter.include`", false);
        assertUnobservedFailure("""
                [resources.filter]
                include = []
                """, observations, "`resources.filter.include`", true);
        assertUnobservedFailure("""
                [resources.filter]
                targets = ["main", "main"]
                include = ["*.txt"]
                """, observations, "`resources.filter.targets[1]`", true);
        assertEquals(0, observations.get());
    }

    @Test
    void continuesLeafValidationAfterNonthrowingObservation() {
        assertObservedThenLeafFailure("""
                [resources]
                main = ["src/resources", "src/resources"]
                """, "`resources.main[1]`");
        assertObservedThenLeafFailure("""
                [resources.filter]
                include = ["*.txt", "*.txt"]
                """, "`resources.filter.include[1]`");
        assertObservedThenLeafFailure("""
                [resources.tokens]
                zeta = { env = "BUILD_ID" }
                alpha = { env = "build_id" }
                """, "`resources.tokens.alpha.env`");
    }

    @Test
    void observesTokenCollectionPresenceBeforeRows() {
        ArrayList<AuthoredResources> observed = new ArrayList<>();

        AuthoredResources explicit = decodeResources(
                "[resources.tokens]\n", observed::add).orElseThrow();
        assertEquals(List.of(AuthoredResources.empty()), observed);
        assertEquals(AuthoredResources.empty(), explicit);

        observed.clear();
        AuthoredResources inline = decodeResources(
                "resources = { tokens = {} }\n", observed::add).orElseThrow();
        assertEquals(List.of(AuthoredResources.empty()), observed);
        assertEquals(AuthoredResources.empty(), inline);

        observed.clear();
        AuthoredResources populated = decodeResources("""
                [resources.tokens]
                channel = { value = "preview" }
                """, observed::add).orElseThrow();
        assertEquals(List.of(AuthoredResources.empty()), observed);
        assertEquals(1, populated.tokens().size());

        assertObservedFailure("""
                [resources.tokens]
                invalid = { env = "BAD-NAME" }
                """, "Invalid manifest section `[resources.tokens]`");
    }

    @Test
    void leavesWholeDocumentShapeFailuresUnobserved() {
        AtomicInteger observations = new AtomicInteger();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeResources("""
                        [resources]
                        main = []
                        [resources.tokens]
                        invalid = { }
                        """, ignored -> observations.incrementAndGet()));

        assertTrue(failure.getMessage().contains("must declare exactly one"));
        assertNull(failure.getCause());
        assertEquals(0, observations.get());
    }

    @Test
    void doesNotObserveOmissionAndRequiresNonNullInputs() {
        AtomicInteger observations = new AtomicInteger();

        assertTrue(decodeResources("", ignored -> observations.incrementAndGet()).isEmpty());

        assertEquals(0, observations.get());
        assertThrows(NullPointerException.class, () -> decodeResourcesWithNullIndex());
        assertThrows(NullPointerException.class, () -> decodeResourcesWithNullObserver());
    }

    private static void assertObservedFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeResources(source, ignored -> {
                    throw new IllegalArgumentException("Observed authored resources.");
                }));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("Observed authored resources."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static void assertUnobservedFailure(
            String source,
            AtomicInteger observations,
            String path,
            boolean modelOwned) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeResources(source, ignored -> observations.incrementAndGet()));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        if (modelOwned) {
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        } else {
            assertNull(failure.getCause());
        }
    }

    private static List<String> strings(AuthoredResources.Filter filter) {
        return filter.include().stream().map(Object::toString).toList();
    }

    private static void assertObservedThenLeafFailure(String source, String path) {
        AtomicInteger observations = new AtomicInteger();
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeResources(source, ignored -> observations.incrementAndGet()));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(1, observations.get());
    }
}
