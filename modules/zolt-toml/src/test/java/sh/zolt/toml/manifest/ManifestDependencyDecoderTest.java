package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.toml.ZoltConfigException;

final class ManifestDependencyDecoderTest {
    @Test
    void preservesCompleteDependencyDomainOmission() {
        ManifestDependencyDecoder.Decoded decoded = decode("");

        assertTrue(decoded.dependencies().isEmpty());
        assertTrue(decoded.constraints().isEmpty());
        assertTrue(decoded.policy().isEmpty());
    }

    @Test
    void preservesIndependentExplicitEmptyCollectionPresence() {
        ManifestDependencyDecoder.Decoded decoded = decode("""
                [dependencies]

                [dependencies.constraints]
                """);

        assertTrue(decoded.dependencies().orElseThrow().declarations().isEmpty());
        assertTrue(decoded.constraints().orElseThrow().entries().isEmpty());
        assertTrue(decoded.policy().isEmpty());
    }

    @Test
    void childDomainsDoNotMaterializeImplicitDependencyDeclarations() {
        ManifestDependencyDecoder.Decoded decoded = decode("""
                [dependencies.constraints]
                "org.example:constraint" = "1.0"

                [dependencies.policy]
                conflicts = "fail"
                """);

        assertTrue(decoded.dependencies().isEmpty());
        assertTrue(decoded.constraints().isPresent());
        assertTrue(decoded.policy().isPresent());
    }

    @Test
    void coordinatesAllThreeDomainsWithoutResolvingReferences() {
        ManifestDependencyDecoder.Decoded decoded = decode("""
                [dependencies]
                "org.example:library" = { versionRef = "not-declared-here" }

                [dependencies.constraints]
                "org.example:constraint" = { versionRef = "also-missing" }

                [dependencies.policy]
                conflicts = "resolve"
                deny = [{ coordinate = "org.example:blocked" }]
                """);

        DependencySelector.VersionReference dependency = assertInstanceOf(
                DependencySelector.VersionReference.class,
                decoded.dependencies().orElseThrow().declarations().getFirst().selector());
        DependencyConstraintSelector.VersionReference constraint = assertInstanceOf(
                DependencyConstraintSelector.VersionReference.class,
                decoded.constraints().orElseThrow().entries()
                        .get(new DependencyCoordinate("org.example:constraint"))
                        .selector());
        assertEquals("not-declared-here", dependency.alias().value());
        assertEquals("also-missing", constraint.alias().value());
        assertEquals(
                DependencyConflictPolicy.RESOLVE,
                decoded.policy().orElseThrow().conflicts().orElseThrow());
        assertEquals(1, decoded.policy().orElseThrow().deny().size());
    }

    @Test
    void propagatesExactDiagnosticsFromEveryChildDecoder() {
        assertFailure("""
                [dependencies]
                "org.example:library" = "LATEST"
                """, "`dependencies.org.example:library`", "Invalid dependency version");
        assertFailure("""
                [dependencies.constraints]
                "org.example:constraint" = { version = "1.0", reason = " " }
                """, "`dependencies.constraints.org.example:constraint.reason`", "must not be blank");
        assertFailure("""
                [dependencies.policy]
                deny = [{ coordinate = "invalid" }]
                """, "`dependencies.policy.deny[0].coordinate`", "Invalid dependency coordinate");
    }

    @Test
    void reportsTheFirstFailureInCanonicalDependencyDomainOrder() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode("""
                        [dependencies]
                        "org.example:library" = "LATEST"

                        [dependencies.constraints]
                        "org.example:constraint" = "LATEST"

                        [dependencies.policy]
                        deny = [{ coordinate = "invalid" }]
                        """));

        assertTrue(failure.getMessage().contains(
                "`dependencies.org.example:library`"), failure.getMessage());
        assertFalse(failure.getMessage().contains("constraints"), failure.getMessage());
        assertFalse(failure.getMessage().contains("deny[0]"), failure.getMessage());
    }

    private static ManifestDependencyDecoder.Decoded decode(String source) {
        return new ManifestDependencyDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
