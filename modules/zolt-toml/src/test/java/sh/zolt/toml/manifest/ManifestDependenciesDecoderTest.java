package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.toml.ZoltConfigException;

final class ManifestDependenciesDecoderTest {
    @Test
    void distinguishesOmissionAndImplicitParentsFromEveryExplicitEmptyLane() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("""
                [dependencies.constraints]
                "org.example:demo" = "1.0"

                [dependencies.policy]
                conflicts = "fail"
                """).isEmpty());

        for (String section : List.of(
                "dependencies",
                "dependencies.api",
                "dependencies.runtime",
                "dependencies.provided",
                "dependencies.dev",
                "dependencies.test",
                "dependencies.processor",
                "dependencies.test-processor")) {
            Optional<AuthoredDependencies> decoded = decode("[" + section + "]\n");
            assertTrue(decoded.isPresent(), section);
            assertTrue(decoded.orElseThrow().declarations().isEmpty(), section);
        }
    }

    @Test
    void mapsAllLanesInCanonicalOrderAndPreservesOrderWithinEachLane() {
        AuthoredDependencies dependencies = decode("""
                [dependencies]
                "org.example:zeta" = "1.0"
                "org.example:alpha" = "1.0"

                [dependencies.test-processor]
                "org.example:test-processor" = "1.0"

                [dependencies.test]
                "org.example:test" = "1.0"

                [dependencies.api]
                "org.example:api" = "1.0"

                [dependencies.processor]
                "org.example:processor" = "1.0"

                [dependencies.dev]
                "org.example:dev" = "1.0"

                [dependencies.provided]
                "org.example:provided" = "1.0"

                [dependencies.runtime]
                "org.example:runtime" = "1.0"
                """).orElseThrow();

        assertEquals(
                List.of(
                        DependencyLane.IMPLEMENTATION,
                        DependencyLane.IMPLEMENTATION,
                        DependencyLane.API,
                        DependencyLane.RUNTIME,
                        DependencyLane.PROVIDED,
                        DependencyLane.DEV,
                        DependencyLane.TEST,
                        DependencyLane.PROCESSOR,
                        DependencyLane.TEST_PROCESSOR),
                dependencies.declarations().stream().map(AuthoredDependency::lane).toList());
        assertEquals(
                List.of("org.example:zeta", "org.example:alpha"),
                dependencies.inLane(DependencyLane.IMPLEMENTATION).stream()
                        .map(dependency -> dependency.coordinate().value())
                        .toList());
    }

    @Test
    void anchorsOrdinaryVariantConflictsToTheLaterCanonicalDeclaration() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode("""
                        [dependencies]
                        "org.example:demo" = { version = "1.0", optional = true }

                        [dependencies.api]
                        "org.example:demo" = { versionRef = "release", exclude = ["a:b"] }
                        """));

        assertTrue(
                failure.getMessage().contains("`dependencies.api.org.example:demo`"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("cannot appear in both"), failure.getMessage());
    }

    @Test
    void permitsDistinctVariantsAndProcessorReuseWithoutResolution() {
        AuthoredDependencies dependencies = decode("""
                [dependencies]
                "org.example:demo" = "1.0"

                [dependencies.runtime]
                "org.example:demo" = { version = "2.0", classifier = "tests" }

                [dependencies.processor]
                "org.example:demo" = { versionRef = "not-declared-here" }

                [dependencies.test-processor]
                "org.example:demo" = { managed = true }
                """).orElseThrow();

        assertEquals(4, dependencies.declarations().size());
        assertEquals(1, dependencies.inLane(DependencyLane.PROCESSOR).size());
        assertEquals(1, dependencies.inLane(DependencyLane.TEST_PROCESSOR).size());
        assertFalse(dependencies.inLane(DependencyLane.RUNTIME)
                .getFirst()
                .variant()
                .isDefaultArtifact());
        assertThrows(
                UnsupportedOperationException.class,
                () -> dependencies.declarations().clear());
    }

    @Test
    void observesFirstCanonicalCollectionPresenceBeforeItsRows() {
        AtomicInteger observations = new AtomicInteger();
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> new ManifestDependenciesDecoder().decode(
                        ManifestSemanticTestSupport.index("""
                                [dependencies.api]
                                "org.example:later" = "LATEST"

                                [dependencies]
                                """),
                        dependencies -> {
                            assertTrue(dependencies.declarations().isEmpty());
                            observations.incrementAndGet();
                            throw new IllegalArgumentException("Observed dependencies.");
                        }));

        assertEquals(1, observations.get());
        assertTrue(failure.getMessage().contains(
                "Invalid manifest section `[dependencies]`: Observed dependencies."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void requiresObserverAndDoesNotObserveOmission() {
        AtomicInteger observations = new AtomicInteger();
        assertTrue(new ManifestDependenciesDecoder()
                .decode(
                        ManifestSemanticTestSupport.index(""),
                        ignored -> observations.incrementAndGet())
                .isEmpty());
        assertEquals(0, observations.get());
        assertThrows(
                NullPointerException.class,
                () -> new ManifestDependenciesDecoder()
                        .decode(ManifestSemanticTestSupport.index(""), null));
    }

    private static Optional<AuthoredDependencies> decode(String source) {
        return new ManifestDependenciesDecoder()
                .decode(ManifestSemanticTestSupport.index(source), ignored -> {});
    }
}
