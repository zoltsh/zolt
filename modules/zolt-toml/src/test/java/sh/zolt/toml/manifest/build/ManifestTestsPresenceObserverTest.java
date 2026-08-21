package sh.zolt.toml.manifest.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeTests;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeTestsWithNullIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.ZoltConfigException;

final class ManifestTestsPresenceObserverTest {
    @Test
    void observesOnlyTheFirstCanonicalFieldInReverseSourceOrder() {
        ArrayList<AuthoredTests> observed = new ArrayList<>();
        String source = """
                [test.suites.smoke]
                tags = ["smoke"]

                [test.integration]
                resources = ["src/integration/resources"]
                sources = ["src/integration/java"]

                [test.runtime]
                events = ["failed"]
                env = { CI = "true" }
                properties = { mode = "test" }
                jvmArgs = ["-ea"]

                [test.sources]
                groovy = ["src/test/groovy"]
                java = ["src/test/java"]
                """;

        AuthoredTests complete = decodeTests(source, observed::add).orElseThrow();

        assertEquals(List.of(AuthoredTests.empty()), observed);
        AuthoredTests first = observed.getFirst();
        assertThrows(UnsupportedOperationException.class, first.suites()::clear);
        assertTrue(complete.sources().isPresent());
        assertTrue(complete.runtime().isPresent());
        assertTrue(complete.integration().isPresent());
        assertEquals(1, complete.suites().size());
        assertObservedFailure(source, "`test.sources.java`");
    }

    @Test
    void observesEachCanonicalDirectFieldAtItsExactAnchor() {
        for (Fixture fixture : List.of(
                new Fixture("test.sources.java = []\n", "`test.sources.java`"),
                new Fixture("test.sources.groovy = []\n", "`test.sources.groovy`"),
                new Fixture("test.runtime.jvmArgs = []\n", "`test.runtime.jvmArgs`"),
                new Fixture("test.runtime.properties = {}\n", "`test.runtime.properties`"),
                new Fixture("test.runtime.env = {}\n", "`test.runtime.env`"),
                new Fixture("test.runtime.events = []\n", "`test.runtime.events`"),
                new Fixture("test.integration.sources = []\n", "`test.integration.sources`"),
                new Fixture("test.integration.resources = []\n", "`test.integration.resources`"))) {
            assertObservedFailure(fixture.source(), fixture.anchor());
        }
    }

    @Test
    void observesExplicitInlineAndImplicitSuiteCollectionPresence() {
        for (String source : List.of(
                "[test.suites]\n",
                "test = { suites = {} }\n")) {
            ArrayList<AuthoredTests> observed = new ArrayList<>();

            AuthoredTests complete = decodeTests(source, observed::add).orElseThrow();

            assertEquals(List.of(AuthoredTests.empty()), observed, source);
            assertEquals(AuthoredTests.empty(), complete, source);
        }

        assertObservedFailure("""
                [test.suites.invalid]
                classes = ["com/example/BadTest"]
                """, "[test.suites]");
    }

    @Test
    void permitsEmptyEarlierFieldsWhenLaterSiblingsMakeChildrenMeaningful() {
        assertDeferredEmptySuccess("""
                test.sources.java = []
                test.sources.groovy = ["src/test/groovy"]
                """, tests -> tests.sources().isPresent());
        assertDeferredEmptySuccess("""
                test.runtime.jvmArgs = []
                test.runtime.properties = { mode = "test" }
                """, tests -> tests.runtime().isPresent());
        assertDeferredEmptySuccess("""
                test.integration.sources = []
                test.integration.resources = ["src/integration/resources"]
                """, tests -> tests.integration().isPresent());
    }

    @Test
    void continuesCanonicalChildValidationAfterObservation() {
        assertObservedThenLeafFailure(
                "test.sources.java = [\"custom/java\", \"custom/java\"]\n",
                "`test.sources.java[1]`");
        assertObservedThenLeafFailure(
                "test.runtime.jvmArgs = [\"-ea\", \"${project.root}\"]\n",
                "`test.runtime.jvmArgs[1]`");
        assertObservedThenLeafFailure(
                "test.integration.sources = [\"custom/integration\", \"custom/integration\"]\n",
                "`test.integration.sources[1]`");
        assertObservedThenLeafFailure("""
                [test.suites.invalid]
                classes = ["com/example/BadTest"]
                """, "`test.suites.invalid.classes[0]`");
        assertObservedThenLeafFailure("""
                [test.runtime]
                events = ["failed"]
                [test.sources]
                java = []
                """, "`test.sources.java`");
    }

    @Test
    void leavesWholeDocumentShapeFailuresUnobserved() {
        AtomicInteger observations = new AtomicInteger();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeTests("""
                        [test.sources]
                        java = ["src/test/java"]
                        unknown = true
                        """, ignored -> observations.incrementAndGet()));

        assertTrue(failure.getMessage().contains("Unknown manifest field"), failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, observations.get());
    }

    @Test
    void doesNotObserveOmissionAndRequiresNonNullInputs() {
        AtomicInteger observations = new AtomicInteger();

        assertTrue(decodeTests("", ignored -> observations.incrementAndGet()).isEmpty());
        assertEquals(0, observations.get());

        NullPointerException indexFailure = assertThrows(
                NullPointerException.class, () -> decodeTestsWithNullIndex());
        assertEquals("Manifest decode index is required.", indexFailure.getMessage());
        NullPointerException observerFailure = assertThrows(
                NullPointerException.class, () -> decodeTests("", null));
        assertEquals(
                "Authored tests presence observer is required.",
                observerFailure.getMessage());
    }

    private static void assertObservedFailure(String source, String anchor) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeTests(source, ignored -> {
                    throw new IllegalArgumentException("Observed authored tests.");
                }));
        assertTrue(failure.getMessage().contains(anchor), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("Observed authored tests."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static void assertObservedThenLeafFailure(String source, String path) {
        AtomicInteger observations = new AtomicInteger();
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeTests(source, ignored -> observations.incrementAndGet()));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(1, observations.get());
    }

    private static void assertDeferredEmptySuccess(
            String source, Predicate<AuthoredTests> childPresent) {
        ArrayList<AuthoredTests> observed = new ArrayList<>();

        AuthoredTests complete = decodeTests(source, observed::add).orElseThrow();

        assertEquals(List.of(AuthoredTests.empty()), observed, source);
        assertTrue(childPresent.test(complete), source);
    }

    private record Fixture(String source, String anchor) {
    }
}
