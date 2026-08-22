package sh.zolt.resolve.traversal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.maven.repository.RawPomDependency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyScopeParserTest {
    private final DependencyScopeParser parser = new DependencyScopeParser();

    @Test
    void missingScopeDefaultsToCompile() {
        DependencyScope scope = parser.parse(dependency(Optional.empty()));

        assertEquals(DependencyScope.COMPILE, scope);
        assertTrue(scope.entersMainCompileClasspath());
        assertTrue(scope.entersMainRuntimeClasspath());
        assertTrue(scope.packagedByDefault());
    }

    @Test
    void testDependenciesDoNotEnterMainClasspaths() {
        DependencyScope scope = parser.parse(dependency(Optional.of("test")));

        assertEquals(DependencyScope.TEST, scope);
        assertFalse(scope.entersMainCompileClasspath());
        assertFalse(scope.entersMainRuntimeClasspath());
        assertTrue(scope.entersTestCompileClasspath());
        assertTrue(scope.entersTestRuntimeClasspath());
        assertFalse(scope.packagedByDefault());
    }

    @Test
    void runtimeDependenciesDoNotEnterCompileClasspath() {
        DependencyScope scope = parser.parse(dependency(Optional.of("runtime")));

        assertEquals(DependencyScope.RUNTIME, scope);
        assertFalse(scope.entersMainCompileClasspath());
        assertTrue(scope.entersMainRuntimeClasspath());
        assertTrue(scope.packagedByDefault());
    }

    @Test
    void providedDependenciesCompileButDoNotRunOrPackageByDefault() {
        DependencyScope scope = parser.parse(dependency(Optional.of("provided")));

        assertEquals(DependencyScope.PROVIDED, scope);
        assertTrue(scope.entersMainCompileClasspath());
        assertFalse(scope.entersMainRuntimeClasspath());
        assertFalse(scope.packagedByDefault());
    }

    @Test
    void unsupportedScopeFailsWithDependencyContext() {
        DependencyScopeException exception = assertThrows(
                DependencyScopeException.class,
                () -> parser.parse(dependency(Optional.of("system"))));

        assertEquals(
                "Unsupported dependency scope `system` for com.example:demo. Supported scopes are compile, runtime, test, and provided.",
                exception.getMessage());
    }

    private static RawPomDependency dependency(Optional<String> scope) {
        return new RawPomDependency(
                "com.example",
                "demo",
                Optional.of("1.0.0"),
                scope,
                Optional.empty(),
                Optional.empty(),
                false,
                List.of());
    }
}
