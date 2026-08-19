package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;

final class DependencyVariantTest {
    @Test
    void normalizesTheDefaultUnclassifiedJarIdentity() {
        DependencyVariant implicit = dependency(AuthoredDependencyMetadata.none()).variant();
        DependencyVariant explicit = dependency(metadata(Optional.empty(), Optional.of("jar"))).variant();

        assertEquals(implicit, explicit);
        assertEquals("com.example:client|jar", implicit.key());
        assertEquals("jar", implicit.artifactKey());
        assertTrue(implicit.isDefaultArtifact());
    }

    @Test
    void classifierAndTypeCreateDistinctCanonicalVariants() {
        DependencyVariant plain = dependency(AuthoredDependencyMetadata.none()).variant();
        DependencyVariant tests = dependency(metadata(Optional.of("tests"), Optional.empty())).variant();
        DependencyVariant zip = dependency(metadata(Optional.empty(), Optional.of("zip"))).variant();

        assertNotEquals(plain, tests);
        assertNotEquals(plain, zip);
        assertEquals("jar|tests", tests.artifactKey());
        assertEquals("com.example:client|jar|tests", tests.key());
        assertFalse(tests.isDefaultArtifact());
        assertFalse(zip.isDefaultArtifact());
    }

    private static AuthoredDependency dependency(AuthoredDependencyMetadata metadata) {
        return new AuthoredDependency(
                DependencyLane.IMPLEMENTATION,
                new DependencyCoordinate("com.example:client"),
                new DependencySelector.FixedVersion("1.0.0"),
                metadata);
    }

    private static AuthoredDependencyMetadata metadata(Optional<String> classifier, Optional<String> type) {
        return new AuthoredDependencyMetadata(false, false, classifier, type, List.of());
    }
}
