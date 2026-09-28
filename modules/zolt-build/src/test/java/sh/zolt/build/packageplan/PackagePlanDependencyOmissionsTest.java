package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.DependencyScope;
import org.junit.jupiter.api.Test;

final class PackagePlanDependencyOmissionsTest {
    @Test
    void rulesUseStableNamesForNonPackagedScopes() {
        assertEquals("provided-container-omitted", PackagePlanDependencyOmissions.rule(DependencyScope.PROVIDED, false));
        assertEquals("dev-only-omitted", PackagePlanDependencyOmissions.rule(DependencyScope.DEV, false));
        assertEquals("processor-omitted", PackagePlanDependencyOmissions.rule(DependencyScope.PROCESSOR, false));
        assertEquals("spring-aot-tool-omitted", PackagePlanDependencyOmissions.rule(DependencyScope.TOOL_SPRING_AOT, false));
        assertEquals("exec-tool-omitted", PackagePlanDependencyOmissions.rule(DependencyScope.TOOL_EXEC, false));
        assertEquals("coverage-tool-omitted", PackagePlanDependencyOmissions.rule(DependencyScope.TOOL_COVERAGE, false));
        assertEquals("groovy-tool-omitted", PackagePlanDependencyOmissions.rule(DependencyScope.TOOL_GROOVY, false));
    }

    @Test
    void springBootWarProvidedRuleUsesProvidedLib() {
        assertEquals("spring-boot-war-provided-lib", PackagePlanDependencyOmissions.rule(DependencyScope.PROVIDED, true));
        assertEquals(
                "provided dependency is placed in WEB-INF/lib-provided",
                PackagePlanDependencyOmissions.reason(DependencyScope.PROVIDED, true));
    }

    @Test
    void reasonsExplainUserVisibleOmissionCause() {
        assertEquals(
                "test dependency is excluded from main package artifacts",
                PackagePlanDependencyOmissions.reason(DependencyScope.TEST, false));
        assertEquals(
                "OpenAPI generator dependency is build-time tooling, not package runtime",
                PackagePlanDependencyOmissions.reason(DependencyScope.TOOL_OPENAPI, false));
        assertEquals(
                "Groovy compiler dependency is build-time tooling, not package runtime",
                PackagePlanDependencyOmissions.reason(DependencyScope.TOOL_GROOVY, false));
    }
}
