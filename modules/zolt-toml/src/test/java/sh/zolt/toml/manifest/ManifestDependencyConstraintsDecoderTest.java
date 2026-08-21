package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.toml.ZoltConfigException;

final class ManifestDependencyConstraintsDecoderTest {
    @Test
    void distinguishesOmissionFromAnExplicitEmptyConstraintCollection() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("""
                [dependencies]
                "org.example:base" = "1.0"

                [dependencies.api]
                "org.example:api" = "1.0"

                [dependencies.policy]
                conflicts = "fail"
                """).isEmpty());

        AuthoredDependencyConstraints empty = decode("""
                [dependencies.constraints]
                """).orElseThrow();

        assertTrue(empty.entries().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> empty.entries().clear());
    }

    @Test
    void decodesEverySelectorAndSortsTheImmutableModelByCoordinate() {
        AuthoredDependencyConstraints constraints = decode("""
                [dependencies.constraints]
                "org.example:zeta" = { versionRef = "not-declared-here", reason = "Align" }
                "org.example:alpha" = "1.0-SNAPSHOT"
                "org.example:middle" = { version = "2.0" }
                """).orElseThrow();

        assertEquals(
                List.of(
                        new DependencyCoordinate("org.example:alpha"),
                        new DependencyCoordinate("org.example:middle"),
                        new DependencyCoordinate("org.example:zeta")),
                List.copyOf(constraints.entries().keySet()));
        assertInstanceOf(
                DependencyConstraintSelector.FixedVersion.class,
                constraints.entries().get(
                        new DependencyCoordinate("org.example:alpha")).selector());
        AuthoredDependencyConstraint referenced = constraints.entries().get(
                new DependencyCoordinate("org.example:zeta"));
        DependencyConstraintSelector.VersionReference reference = assertInstanceOf(
                DependencyConstraintSelector.VersionReference.class,
                referenced.selector());
        assertEquals("not-declared-here", reference.alias().value());
        assertEquals("Align", referenced.reason().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> constraints.entries().clear());
    }

    @Test
    void anchorsSelectorAndReasonFailuresToTheirExactForms() {
        assertFailure(
                "\"LATEST\"",
                "dependencies.constraints.org.example:demo",
                "Invalid dependency constraint version");
        assertFailure(
                "{ version = \"LATEST\" }",
                "dependencies.constraints.org.example:demo.version",
                "Invalid dependency constraint version");
        assertFailure(
                "{ versionRef = \"Bad_Id\" }",
                "dependencies.constraints.org.example:demo.versionRef",
                "Invalid local ID");
        assertFailure(
                "{ version = \"1.0\", reason = \" \" }",
                "dependencies.constraints.org.example:demo.reason",
                "must not be blank");
    }

    @Test
    void leavesClosedSelectorShapeAndCoordinateGrammarToValidation() {
        assertSourceFailure(
                "\"org.example:demo:tests\" = \"1.0\"",
                "Invalid dynamic key `org.example:demo:tests`");
        assertSourceFailure(
                "\"org.example:demo\" = { reason = \"missing\" }",
                "must declare exactly one of `version` or `versionRef`");
        assertSourceFailure(
                "\"org.example:demo\" = { version = \"1.0\", versionRef = \"release\" }",
                "must declare exactly one of `version` or `versionRef`");
        assertSourceFailure(
                "\"org.example:demo\" = { version = \"1.0\", kind = \"strict\" }",
                "Unknown manifest field `dependencies.constraints.org.example:demo.kind`");
    }

    @Test
    void observesCollectionPresenceBeforeItsRows() {
        AtomicInteger observations = new AtomicInteger();
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> new ManifestDependencyConstraintsDecoder().decode(
                        ManifestSemanticTestSupport.index("""
                                [dependencies.constraints]
                                "org.example:later" = "LATEST"
                                """),
                        constraints -> {
                            assertTrue(constraints.entries().isEmpty());
                            observations.incrementAndGet();
                            throw new IllegalArgumentException("Observed constraints.");
                        }));

        assertEquals(1, observations.get());
        assertTrue(failure.getMessage().contains(
                "Invalid manifest section `[dependencies.constraints]`: Observed constraints."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void requiresObserverAndDoesNotObserveOmission() {
        AtomicInteger observations = new AtomicInteger();
        assertTrue(new ManifestDependencyConstraintsDecoder()
                .decode(
                        ManifestSemanticTestSupport.index(""),
                        ignored -> observations.incrementAndGet())
                .isEmpty());
        assertEquals(0, observations.get());
        assertThrows(
                NullPointerException.class,
                () -> new ManifestDependencyConstraintsDecoder()
                        .decode(ManifestSemanticTestSupport.index(""), null));
    }

    private static Optional<AuthoredDependencyConstraints> decode(String source) {
        return new ManifestDependencyConstraintsDecoder()
                .decode(ManifestSemanticTestSupport.index(source), ignored -> {});
    }

    private static void assertFailure(String value, String path, String detail) {
        assertSourceFailure("\"org.example:demo\" = " + value, "`" + path + "`", detail);
    }

    private static void assertSourceFailure(String entry, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode("[dependencies.constraints]\n" + entry + "\n"));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
