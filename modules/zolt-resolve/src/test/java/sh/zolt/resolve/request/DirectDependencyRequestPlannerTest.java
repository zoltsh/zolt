package sh.zolt.resolve.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltTomlParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DirectDependencyRequestPlannerTest {
    private static final PackageId APP = new PackageId("com.example", "app");
    private static final PackageId RUNTIME = new PackageId("com.example", "runtime");
    private static final PackageId MANAGED_TEST = new PackageId("com.example", "managed-test");

    private final DirectDependencyRequestPlanner planner = new DirectDependencyRequestPlanner(new CoordinateParser());

    @Test
    void plansDirectRequestsBySectionScopeAndOrigin() {
        List<DependencyRequest> requests = planner.plan(scopeConfig(), Map.of());

        DependencyRequest app = onlyRequest(requests, APP);
        assertEquals("1.0.0", app.requestedVersion());
        assertEquals(DependencyScope.COMPILE, app.scope());
        assertEquals(RequestOrigin.DIRECT, app.origin());
        assertFalse(app.optional());

        DependencyRequest runtime = onlyRequest(requests, RUNTIME);
        assertEquals("2.0.0", runtime.requestedVersion());
        assertEquals(DependencyScope.RUNTIME, runtime.scope());
        assertEquals(RequestOrigin.DIRECT, runtime.origin());
    }

    @Test
    void marksAnOptionalDeclarationOnTheDirectTraversalRoot() {
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [api.dependencies]
                "com.example:app" = { version = "1.0.0", optional = true }
                """);

        DependencyRequest request =
                onlyRequest(planner.plan(config, Map.of()), APP);

        assertTrue(request.optional());
    }

    @Test
    void appliesMetadataExclusionsToMatchingDirectDependency() {
        ProjectConfig config = scopeConfig().withDependencyMetadata(Map.of(
                DependencyMetadata.key("dependencies", APP.toString()),
                new DependencyMetadata(
                        "dependencies",
                        APP.toString(),
                        "1.0.0",
                        false,
                        null,
                        false,
                        false,
                        List.of(new DependencyExclusionSpec("com.example", "legacy")))));

        DependencyRequest request = onlyRequest(planner.plan(config, Map.of()), APP);

        assertEquals(List.of(new DependencyExclusion("com.example", "legacy")), request.exclusions());
    }

    @Test
    void resolvesManagedDirectRequestVersionsFromProjectManagedVersions() {
        DependencyRequest request = onlyRequest(
                planner.plan(managedTestConfig(), Map.of(MANAGED_TEST, "3.0.0")),
                MANAGED_TEST);

        assertEquals("3.0.0", request.requestedVersion());
        assertEquals(DependencyScope.TEST, request.scope());
    }

    @Test
    void reportsMissingManagedVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> planner.plan(managedTestConfig(), Map.of()));

        assertTrue(exception.getMessage().contains("Dependency com.example:managed-test in [test.dependencies]"));
        assertTrue(exception.getMessage().contains("uses a platform-managed version"));
        assertTrue(exception.getMessage().contains("Add a version or add a platform"));
    }

    @Test
    void rejectsUnsupportedExternalVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> planner.plan(managedTestConfig(), Map.of(MANAGED_TEST, "1.+"), "zolt build"));

        assertTrue(exception.getMessage().contains("Unsupported external dependency version `1.+`"));
        assertTrue(exception.getMessage().contains("com.example:managed-test"));
        assertTrue(exception.getMessage().contains("Use a fixed released version"));
        assertTrue(exception.getMessage().contains("run `zolt build` again"));
        assertTrue(!exception.getMessage().contains("run `zolt resolve` again"));
    }

    @Test
    void rejectsWildcardDependencyExclusionsClearly() {
        ProjectConfig config = scopeConfig().withDependencyMetadata(Map.of(
                DependencyMetadata.key("dependencies", APP.toString()),
                new DependencyMetadata(
                        "dependencies",
                        APP.toString(),
                        "1.0.0",
                        false,
                        null,
                        false,
                        false,
                        List.of(new DependencyExclusionSpec("*", "legacy")))));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> planner.plan(config, Map.of(), "zolt build"));

        assertTrue(exception.getMessage().contains("Wildcard dependency exclusions are not supported"));
        assertTrue(exception.getMessage().contains("*:legacy"));
        assertTrue(exception.getMessage().contains("run `zolt build` again"));
    }

    @Test
    void plansDirectClassifiedRequestWithArtifactDescriptor() {
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "io.netty:netty-transport-native-epoll" = { version = "4.1.100.Final", classifier = "linux-x86_64" }
                """);

        DependencyRequest request = onlyRequest(
                planner.plan(config, Map.of()),
                new PackageId("io.netty", "netty-transport-native-epoll"));

        assertTrue(request.artifactDescriptor().isPresent());
        ArtifactDescriptor descriptor = request.artifactDescriptor().orElseThrow();
        assertEquals(Optional.of("linux-x86_64"), descriptor.classifier());
        assertEquals("jar", descriptor.extension());
        assertEquals("4.1.100.Final", descriptor.coordinate().version().orElseThrow());
    }

    @Test
    void plansDirectTypedRequestWithArtifactDescriptor() {
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "com.example:native" = { version = "1.0.0", type = "so" }
                """);

        DependencyRequest request = onlyRequest(
                planner.plan(config, Map.of()),
                new PackageId("com.example", "native"));

        ArtifactDescriptor descriptor = request.artifactDescriptor().orElseThrow();
        assertEquals(Optional.empty(), descriptor.classifier());
        assertEquals("so", descriptor.extension());
    }

    @Test
    void plainDirectDependencyHasNoArtifactDescriptor() {
        DependencyRequest request = onlyRequest(planner.plan(scopeConfig(), Map.of()), APP);

        assertTrue(request.artifactDescriptor().isEmpty());
    }

    private static DependencyRequest onlyRequest(List<DependencyRequest> requests, PackageId packageId) {
        return requests.stream()
                .filter(request -> request.packageId().equals(packageId))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectConfig scopeConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "com.example:app" = "1.0.0"

                [runtime.dependencies]
                "com.example:runtime" = "2.0.0"
                """);
    }

    private static ProjectConfig managedTestConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [test.dependencies]
                "com.example:managed-test" = {}
                """);
    }

}
