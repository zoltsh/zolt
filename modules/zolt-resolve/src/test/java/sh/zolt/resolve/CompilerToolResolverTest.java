package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;

final class CompilerToolResolverTest {
    @Test
    void acceptsAnExactSameCoordinateCompilerRoot() {
        Optional<ArtifactDescriptor> variant = Optional.of(new ArtifactDescriptor(
                new Coordinate(
                        "org.jetbrains.kotlin",
                        "kotlin-compiler-embeddable",
                        Optional.of("2.2.0")),
                Optional.of("all"),
                "zip"));
        DependencyRequest original = request(
                "org.jetbrains.kotlin",
                "kotlin-compiler-embeddable",
                "2.2.0",
                DependencyScope.TOOL_KOTLIN,
                variant);
        DependencyRequest unchanged = request(
                "org.jetbrains.kotlin",
                "kotlin-compiler-embeddable",
                "2.2.0",
                DependencyScope.TOOL_KOTLIN,
                variant);

        assertDoesNotThrow(() -> CompilerToolResolver.requireUnrelocatedRoot(
                original,
                unchanged,
                "zolt resolve"));
    }

    @Test
    void defensivelyTreatsTheArtifactVariantAsPartOfTheExactCompilerRoot() {
        DependencyRequest original = request(
                "org.jetbrains.kotlin",
                "kotlin-compiler-embeddable",
                "2.2.0",
                DependencyScope.TOOL_KOTLIN,
                Optional.empty());
        DependencyRequest relocated = request(
                "org.jetbrains.kotlin",
                "kotlin-compiler-embeddable",
                "2.2.0",
                DependencyScope.TOOL_KOTLIN,
                Optional.of(new ArtifactDescriptor(
                        new Coordinate(
                                "org.jetbrains.kotlin",
                                "kotlin-compiler-embeddable",
                                Optional.of("2.2.0")),
                        Optional.of("all"),
                        "zip")));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> CompilerToolResolver.requireUnrelocatedRoot(
                        original,
                        relocated,
                        "zolt resolve"));

        assertTrue(exception.getMessage().contains(
                "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0:zip|all"));
    }

    @Test
    void ordinaryDependencyRelocationRemainsAllowedByTheCompilerGuard() {
        DependencyRequest original = request(
                "com.legacy",
                "library",
                "1.0.0",
                DependencyScope.COMPILE,
                Optional.empty());
        DependencyRequest relocated = request(
                "com.modern",
                "library",
                "2.0.0",
                DependencyScope.COMPILE,
                Optional.empty());

        assertDoesNotThrow(() -> CompilerToolResolver.requireUnrelocatedRoot(
                original,
                relocated,
                "zolt resolve"));
    }

    private static DependencyRequest request(
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            Optional<ArtifactDescriptor> descriptor) {
        return new DependencyRequest(
                new PackageId(groupId, artifactId),
                version,
                scope,
                RequestOrigin.DIRECT,
                descriptor);
    }
}
