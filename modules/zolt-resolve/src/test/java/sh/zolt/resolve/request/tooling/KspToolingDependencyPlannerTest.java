package sh.zolt.resolve.request.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;

final class KspToolingDependencyPlannerTest {
    private final KspToolingDependencyPlanner planner =
            new KspToolingDependencyPlanner(new CoordinateParser());

    @Test
    void plansSeparateEngineAndProcessorClosures() {
        Map<String, List<DependencyRequest>> groups = planner.groups(List.of(settings(
                "ksp",
                "2.2.0-2.0.2",
                processor("com.example:first", "1.0.0"),
                processor("com.example:second", "2.0.0"))));

        assertEquals(List.of("ksp:ksp:engine", "ksp:ksp:processors"),
                groups.keySet().stream().toList());
        assertRequest(
                groups.get("ksp:ksp:engine").getFirst(),
                "com.google.devtools.ksp",
                "symbol-processing-aa",
                "2.2.0-2.0.2");
        assertEquals(2, groups.get("ksp:ksp:processors").size());
        assertRequest(
                groups.get("ksp:ksp:processors").getFirst(),
                "com.example",
                "first",
                "1.0.0");
        assertThrows(UnsupportedOperationException.class, groups::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> groups.get("ksp:ksp:processors").clear());
    }

    @Test
    void deduplicatesOneToolContractReusedByMultipleSteps() {
        KspGenerationSettings settings = settings(
                "symbols", "2.2.0-2.0.2", processor("com.example:first", "1.0.0"));

        Map<String, List<DependencyRequest>> groups = planner.groups(List.of(settings, settings));

        assertEquals(2, groups.size());
        assertEquals(1, groups.get("ksp:symbols:engine").size());
        assertEquals(1, groups.get("ksp:symbols:processors").size());
    }

    @Test
    void isolatesDifferentNamedToolsEvenWhenTheyShareArtifacts() {
        KspProcessorSettings shared = processor("com.example:first", "1.0.0");

        Map<String, List<DependencyRequest>> groups = planner.groups(List.of(
                settings("alpha", "2.2.0-2.0.2", shared),
                settings("beta", "2.2.0-2.0.2", shared)));

        assertEquals(List.of(
                        "ksp:alpha:engine",
                        "ksp:alpha:processors",
                        "ksp:beta:engine",
                        "ksp:beta:processors"),
                groups.keySet().stream().toList());
    }

    @Test
    void rejectsInconsistentReuseOfAToolName() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> planner.groups(List.of(
                        settings("ksp", "2.2.0-2.0.2", processor("com.example:first", "1.0.0")),
                        settings("ksp", "2.2.10-2.0.2", processor("com.example:first", "1.0.0")))));

        assertEquals(
                "KSP tool `ksp` has inconsistent engine or processor requests across generated steps.",
                exception.getMessage());
    }

    private static KspGenerationSettings settings(
            String tool,
            String version,
            KspProcessorSettings... processors) {
        return new KspGenerationSettings(
                tool,
                Optional.of(version),
                Optional.empty(),
                List.of(processors),
                Map.of("mode", "test"));
    }

    private static KspProcessorSettings processor(String coordinate, String version) {
        return new KspProcessorSettings(coordinate, version, Optional.empty());
    }

    private static void assertRequest(
            DependencyRequest request,
            String group,
            String artifact,
            String version) {
        assertEquals(group, request.packageId().groupId());
        assertEquals(artifact, request.packageId().artifactId());
        assertEquals(version, request.requestedVersion());
        assertEquals(DependencyScope.TOOL_EXEC, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());
    }
}
