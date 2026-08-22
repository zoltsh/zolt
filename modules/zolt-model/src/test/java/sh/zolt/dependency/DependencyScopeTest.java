package sh.zolt.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class DependencyScopeTest {
    @Test
    void lockfileNamesStayStableForEveryScope() {
        Map<DependencyScope, String> names = Arrays.stream(DependencyScope.values())
                .collect(Collectors.toMap(scope -> scope, DependencyScope::lockfileName));

        assertEquals("compile", names.get(DependencyScope.COMPILE));
        assertEquals("runtime", names.get(DependencyScope.RUNTIME));
        assertEquals("dev", names.get(DependencyScope.DEV));
        assertEquals("test", names.get(DependencyScope.TEST));
        assertEquals("provided", names.get(DependencyScope.PROVIDED));
        assertEquals("processor", names.get(DependencyScope.PROCESSOR));
        assertEquals("test-processor", names.get(DependencyScope.TEST_PROCESSOR));
        assertEquals("quarkus-deployment", names.get(DependencyScope.QUARKUS_DEPLOYMENT));
        assertEquals("tool-spring-aot", names.get(DependencyScope.TOOL_SPRING_AOT));
        assertEquals("tool-openapi", names.get(DependencyScope.TOOL_OPENAPI));
        assertEquals("tool-protobuf", names.get(DependencyScope.TOOL_PROTOBUF));
        assertEquals("tool-exec", names.get(DependencyScope.TOOL_EXEC));
        assertEquals("tool-coverage", names.get(DependencyScope.TOOL_COVERAGE));
    }

    @Test
    void compileScopeEntersMainCompileRuntimeAndPackageLanes() {
        DependencyScope scope = DependencyScope.COMPILE;

        assertTrue(scope.entersMainCompileClasspath());
        assertTrue(scope.entersMainRuntimeClasspath());
        assertTrue(scope.packagedByDefault());
        assertTrue(scope.entersTestCompileClasspath());
        assertTrue(scope.entersTestRuntimeClasspath());
        assertFalse(scope.entersMainProcessorClasspath());
        assertFalse(scope.entersTestProcessorClasspath());
    }

    @Test
    void specializedScopesEnterOnlyTheirOwnedLanes() {
        assertTrue(DependencyScope.TEST.entersTestCompileClasspath());
        assertTrue(DependencyScope.TEST.entersTestRuntimeClasspath());
        assertTrue(DependencyScope.PROCESSOR.entersMainProcessorClasspath());
        assertTrue(DependencyScope.TEST_PROCESSOR.entersTestProcessorClasspath());

        assertFalse(DependencyScope.PROVIDED.entersMainRuntimeClasspath());
        assertFalse(DependencyScope.DEV.packagedByDefault());
        assertFalse(DependencyScope.TOOL_COVERAGE.entersMainCompileClasspath());
        assertFalse(DependencyScope.QUARKUS_DEPLOYMENT.packagedByDefault());
    }

    @Test
    void testLanesCoverCompileRuntimeProvidedAndTestScopesOnly() {
        Set<DependencyScope> onTestLanes = Set.of(
                DependencyScope.COMPILE,
                DependencyScope.RUNTIME,
                DependencyScope.PROVIDED,
                DependencyScope.TEST);

        for (DependencyScope scope : DependencyScope.values()) {
            assertEquals(
                    onTestLanes.contains(scope),
                    scope.entersTestCompileClasspath(),
                    scope + " test compile classpath membership");
            assertEquals(
                    onTestLanes.contains(scope),
                    scope.entersTestRuntimeClasspath(),
                    scope + " test runtime classpath membership");
        }
    }
}
