package sh.zolt.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.dependency.DependencyScope;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClasspathLanePolicyTest {
    @Test
    void regularScopesMapToExpectedClasspathLanes() {
        assertEquals(List.of("compile", "runtime", "test"), ClasspathLanePolicy.lanes(DependencyScope.COMPILE));
        assertEquals(List.of("runtime", "test"), ClasspathLanePolicy.lanes(DependencyScope.RUNTIME));
        assertEquals(List.of("runtime"), ClasspathLanePolicy.lanes(DependencyScope.DEV));
        assertEquals(List.of("compile", "test"), ClasspathLanePolicy.lanes(DependencyScope.PROVIDED));
        assertEquals(List.of("test"), ClasspathLanePolicy.lanes(DependencyScope.TEST));
        assertEquals(List.of("processor"), ClasspathLanePolicy.lanes(DependencyScope.PROCESSOR));
        assertEquals(List.of("test-processor"), ClasspathLanePolicy.lanes(DependencyScope.TEST_PROCESSOR));

        assertThrows(
                UnsupportedOperationException.class,
                () -> ClasspathLanePolicy.lanes(DependencyScope.COMPILE).add("custom"));
    }

    @Test
    void toolingScopesMapToToolingOnlyLanesAndDispositions() {
        assertEquals(List.of("quarkus-deployment"), ClasspathLanePolicy.lanes(DependencyScope.QUARKUS_DEPLOYMENT));
        assertEquals(List.of("tool-spring-aot"), ClasspathLanePolicy.lanes(DependencyScope.TOOL_SPRING_AOT));
        assertEquals(List.of("tool-openapi"), ClasspathLanePolicy.lanes(DependencyScope.TOOL_OPENAPI));
        assertEquals(List.of("tool-protobuf"), ClasspathLanePolicy.lanes(DependencyScope.TOOL_PROTOBUF));
        assertEquals(List.of("tool-exec"), ClasspathLanePolicy.lanes(DependencyScope.TOOL_EXEC));
        assertEquals(List.of("tool-coverage"), ClasspathLanePolicy.lanes(DependencyScope.TOOL_COVERAGE));

        assertEquals("package-default", ClasspathLanePolicy.disposition(DependencyScope.COMPILE));
        assertEquals("provided-container", ClasspathLanePolicy.disposition(DependencyScope.PROVIDED));
        assertEquals("development-only", ClasspathLanePolicy.disposition(DependencyScope.DEV));
        assertEquals("test-only", ClasspathLanePolicy.disposition(DependencyScope.TEST));
        assertEquals("processor-only", ClasspathLanePolicy.disposition(DependencyScope.PROCESSOR));
        assertEquals("processor-only", ClasspathLanePolicy.disposition(DependencyScope.TEST_PROCESSOR));
        assertEquals("quarkus-augmentation-only", ClasspathLanePolicy.disposition(DependencyScope.QUARKUS_DEPLOYMENT));
        assertEquals("spring-aot-tooling-only", ClasspathLanePolicy.disposition(DependencyScope.TOOL_SPRING_AOT));
        assertEquals("openapi-generator-tooling-only", ClasspathLanePolicy.disposition(DependencyScope.TOOL_OPENAPI));
        assertEquals("protobuf-generator-tooling-only", ClasspathLanePolicy.disposition(DependencyScope.TOOL_PROTOBUF));
        assertEquals("exec-tooling-only", ClasspathLanePolicy.disposition(DependencyScope.TOOL_EXEC));
        assertEquals("coverage-tooling-only", ClasspathLanePolicy.disposition(DependencyScope.TOOL_COVERAGE));
    }
}
