package sh.zolt.resolve.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltTomlParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyRequestPlannerTest {
    private static final PackageId APP = new PackageId("com.example", "app");
    private static final PackageId TEST_APP = new PackageId("com.example", "test-app");
    private static final PackageId JUNIT_CONSOLE = new PackageId("org.junit.platform", "junit-platform-console");

    private final DependencyRequestPlanner planner = new DependencyRequestPlanner(
            new CoordinateParser(),
            new ToolingDependencyContributor(new CoordinateParser()));

    @Test
    void plansDirectRequestsWithMetadataExclusions() {
        ProjectConfig config = baseConfig().withDependencyMetadata(Map.of(
                DependencyMetadata.key("dependencies", APP.toString()),
                new DependencyMetadata(
                        "dependencies",
                        APP.toString(),
                        "1.0.0",
                        false,
                        null,
                        false,
                        false,
                        List.of(new DependencyExclusionSpec("com.example", "lib")))));

        DependencyRequest request = onlyRequest(planner.plan(config, Map.of(), false), APP);

        assertEquals("1.0.0", request.requestedVersion());
        assertEquals(DependencyScope.COMPILE, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());
        assertEquals(RequestVersionOrigin.DECLARED, request.versionOrigin());
        assertEquals(List.of(new DependencyExclusion("com.example", "lib")), request.exclusions());
    }

    @Test
    void resolvesManagedDirectRequestsFromProjectManagedVersions() {
        List<DependencyRequest> requests = planner.plan(
                managedTestDependencyConfig(),
                Map.of(TEST_APP, "2.0.0"),
                false);

        DependencyRequest request = onlyRequest(requests, TEST_APP);
        assertEquals("2.0.0", request.requestedVersion());
        assertEquals(DependencyScope.TEST, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());
        assertEquals(RequestVersionOrigin.MANAGED, request.versionOrigin());
    }

    @Test
    void reportsMissingManagedVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> planner.plan(managedTestDependencyConfig(), Map.of(), false));

        assertTrue(exception.getMessage().contains("Dependency com.example:test-app in [test.dependencies]"));
        assertTrue(exception.getMessage().contains("uses a platform-managed version"));
        assertTrue(exception.getMessage().contains("Add a version or add a platform"));
    }

    @Test
    void rejectsUnsupportedManagedVersionFromPlanningInputs() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> planner.plan(managedTestDependencyConfig(), Map.of(TEST_APP, "1.+"), false, "zolt build"));

        assertTrue(exception.getMessage().contains("Unsupported external dependency version `1.+`"));
        assertTrue(exception.getMessage().contains("com.example:test-app"));
        assertTrue(exception.getMessage().contains("fixed released version"));
        assertTrue(exception.getMessage().contains("run `zolt build` again"));
    }

    @Test
    void rejectsPolicyExcludedRequestsAfterToolingContribution() {
        ProjectConfig config = testDependencyConfig().withDependencyPolicy(new DependencyPolicySettings(
                List.of(new DependencyPolicyExclusion(
                        "org.junit.platform",
                        "junit-platform-console",
                        Optional.of("Use an internal test launcher"))),
                Map.of()));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> planner.plan(config, Map.of(JUNIT_CONSOLE, "1.12.0"), false, "zolt build"));

        assertTrue(exception.getMessage()
                .contains("Dependency policy excludes direct dependency `org.junit.platform:junit-platform-console`"));
        assertTrue(exception.getMessage().contains("Use an internal test launcher"));
        assertTrue(exception.getMessage()
                .contains("Remove the direct dependency or remove the matching [dependencyPolicy].exclude entry"));
        assertTrue(exception.getMessage().contains("run `zolt build` again"));
    }

    @Test
    void includesToolingContributorRequests() {
        DependencyRequest request = onlyRequest(
                planner.plan(testDependencyConfig(), Map.of(JUNIT_CONSOLE, "1.12.0"), false),
                JUNIT_CONSOLE);

        assertEquals("1.12.0", request.requestedVersion());
        assertEquals(DependencyScope.TEST, request.scope());
        assertEquals(RequestOrigin.TRANSITIVE, request.origin());
        assertEquals(RequestVersionOrigin.INJECTED, request.versionOrigin());
    }

    private static DependencyRequest onlyRequest(List<DependencyRequest> requests, PackageId packageId) {
        return requests.stream()
                .filter(request -> request.packageId().equals(packageId))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectConfig baseConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "com.example:app" = "1.0.0"
                """);
    }

    private static ProjectConfig managedTestDependencyConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [test.dependencies]
                "com.example:test-app" = {}
                """);
    }

    private static ProjectConfig testDependencyConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [test.dependencies]
                "com.example:app" = "1.0.0"
                """);
    }
}
