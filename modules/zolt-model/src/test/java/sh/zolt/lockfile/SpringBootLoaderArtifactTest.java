package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SpringBootLoaderArtifactTest {
    @Test
    void recognizesOnlyTheDefaultUnclassifiedJarVariant() {
        assertTrue(SpringBootLoaderArtifact.isDefaultLoader(
                SpringBootLoaderArtifact.PACKAGE_ID,
                LockArtifactVariant.defaultVariant()));
        assertFalse(SpringBootLoaderArtifact.isDefaultLoader(
                SpringBootLoaderArtifact.PACKAGE_ID,
                new LockArtifactVariant("jar", Optional.of("tests"))));
        assertFalse(SpringBootLoaderArtifact.isDefaultLoader(
                SpringBootLoaderArtifact.PACKAGE_ID,
                new LockArtifactVariant("zip", Optional.empty())));
        assertFalse(SpringBootLoaderArtifact.isDefaultLoader(
                new PackageId("com.example", "spring-boot-loader"),
                LockArtifactVariant.defaultVariant()));
    }

    @Test
    void appliesTheSameRuleToResolvedIdentityFields() {
        assertTrue(SpringBootLoaderArtifact.isDefaultLoader(
                SpringBootLoaderArtifact.PACKAGE_ID,
                "jar",
                Optional.empty()));
        assertFalse(SpringBootLoaderArtifact.isDefaultLoader(
                SpringBootLoaderArtifact.PACKAGE_ID,
                "jar",
                Optional.of("tests")));
    }
}
