package sh.zolt.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.classpath.NestedArtifactIdentity.SourceKind;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class NestedArtifactIdentityTest {
    @Test
    void nestedNamesSeparateEveryAuthorityDimension() {
        NestedArtifactIdentity base = identity(
                "com.alpha",
                "shared",
                Optional.empty(),
                SourceKind.EXTERNAL);

        assertNotEquals(
                base.nestedJarName(),
                identity("com.beta", "shared", Optional.empty(), SourceKind.EXTERNAL)
                        .nestedJarName());
        assertNotEquals(
                base.nestedJarName(),
                identity("com.alpha", "shared", Optional.of("linux"), SourceKind.EXTERNAL)
                        .nestedJarName());
        assertNotEquals(
                base.nestedJarName(),
                identity("com.alpha", "shared", Optional.empty(), SourceKind.WORKSPACE)
                        .nestedJarName());
    }

    @Test
    void flattenedWorkspaceCoordinatesAndRepeatedPlansRemainDeterministic() {
        NestedArtifactIdentity first =
                identity("com.a-b", "c", Optional.empty(), SourceKind.WORKSPACE);
        NestedArtifactIdentity flattenedCollision =
                identity("com.a", "b-c", Optional.empty(), SourceKind.WORKSPACE);

        assertNotEquals(
                first.nestedJarName(),
                flattenedCollision.nestedJarName());
        assertEquals(
                first.nestedJarName(),
                identity("com.a-b", "c", Optional.empty(), SourceKind.WORKSPACE)
                        .nestedJarName());
    }

    @Test
    void coordinateUsesThePackagePlanVariantQualifier() {
        assertEquals(
                "com.alpha:provider:linux:jar:1.0.0",
                identity(
                                "com.alpha",
                                "provider",
                                Optional.of("linux"),
                                SourceKind.EXTERNAL)
                        .coordinate());
    }

    private static NestedArtifactIdentity identity(
            String group,
            String artifact,
            Optional<String> classifier,
            SourceKind sourceKind) {
        return NestedArtifactIdentity.of(
                new PackageId(group, artifact),
                "1.0.0",
                new LockArtifactVariant("jar", classifier),
                sourceKind);
    }
}
