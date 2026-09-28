package sh.zolt.resolve.traversal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.DependencyScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyTransitiveScopeSelectorTest {
    private final DependencyTransitiveScopeSelector selector = new DependencyTransitiveScopeSelector();

    @Test
    void compileParentsPreserveDependencyScope() {
        assertEquals(
                Optional.of(DependencyScope.RUNTIME),
                selector.select(DependencyScope.COMPILE, DependencyScope.RUNTIME));
        assertEquals(
                Optional.of(DependencyScope.COMPILE),
                selector.select(DependencyScope.COMPILE, DependencyScope.COMPILE));
    }

    @Test
    void testAndProvidedDependenciesDoNotEnterTransitiveGraph() {
        assertEquals(Optional.empty(), selector.select(DependencyScope.COMPILE, DependencyScope.TEST));
        assertEquals(Optional.empty(), selector.select(DependencyScope.COMPILE, DependencyScope.PROVIDED));
    }

    @Test
    void testRuntimeAndDevParentsKeepTheirOwnScope() {
        assertEquals(
                Optional.of(DependencyScope.TEST),
                selector.select(DependencyScope.TEST, DependencyScope.RUNTIME));
        assertEquals(
                Optional.of(DependencyScope.RUNTIME),
                selector.select(DependencyScope.RUNTIME, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.DEV),
                selector.select(DependencyScope.DEV, DependencyScope.COMPILE));
    }

    @Test
    void processorAndToolParentsKeepTheirOwnScope() {
        assertEquals(
                Optional.of(DependencyScope.PROCESSOR),
                selector.select(DependencyScope.PROCESSOR, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.TEST_PROCESSOR),
                selector.select(DependencyScope.TEST_PROCESSOR, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.QUARKUS_DEPLOYMENT),
                selector.select(DependencyScope.QUARKUS_DEPLOYMENT, DependencyScope.RUNTIME));
        assertEquals(
                Optional.of(DependencyScope.TOOL_SPRING_AOT),
                selector.select(DependencyScope.TOOL_SPRING_AOT, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.TOOL_OPENAPI),
                selector.select(DependencyScope.TOOL_OPENAPI, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.TOOL_PROTOBUF),
                selector.select(DependencyScope.TOOL_PROTOBUF, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.TOOL_EXEC),
                selector.select(DependencyScope.TOOL_EXEC, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.TOOL_COVERAGE),
                selector.select(DependencyScope.TOOL_COVERAGE, DependencyScope.COMPILE));
        assertEquals(
                Optional.of(DependencyScope.TOOL_GROOVY),
                selector.select(DependencyScope.TOOL_GROOVY, DependencyScope.RUNTIME));
    }
}
