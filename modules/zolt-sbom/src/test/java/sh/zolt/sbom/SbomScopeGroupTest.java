package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;

final class SbomScopeGroupTest {
    @Test
    void classifiesEveryDependencyScope() {
        Map<DependencyScope, SbomScopeGroup> expected = Map.ofEntries(
                Map.entry(DependencyScope.COMPILE, SbomScopeGroup.REQUIRED),
                Map.entry(DependencyScope.RUNTIME, SbomScopeGroup.REQUIRED),
                Map.entry(DependencyScope.PROVIDED, SbomScopeGroup.PROVIDED),
                Map.entry(DependencyScope.DEV, SbomScopeGroup.DEV),
                Map.entry(DependencyScope.TEST, SbomScopeGroup.TEST),
                Map.entry(DependencyScope.TEST_PROCESSOR, SbomScopeGroup.TEST),
                Map.entry(DependencyScope.PROCESSOR, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.QUARKUS_DEPLOYMENT, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.TOOL_SPRING_AOT, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.TOOL_OPENAPI, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.TOOL_PROTOBUF, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.TOOL_EXEC, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.TOOL_COVERAGE, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.TOOL_GROOVY, SbomScopeGroup.TOOLS),
                Map.entry(DependencyScope.TOOL_KOTLIN, SbomScopeGroup.TOOLS));

        assertEquals(DependencyScope.values().length, expected.size());
        expected.forEach((scope, group) -> assertEquals(group, SbomScopeGroup.of(scope), scope.name()));
    }
}
