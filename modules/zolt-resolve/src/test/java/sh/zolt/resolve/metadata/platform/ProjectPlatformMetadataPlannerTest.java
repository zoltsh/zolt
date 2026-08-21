package sh.zolt.resolve.metadata.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.maven.repository.EffectiveRawPom;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomDependency;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ProjectPlatformMetadataPlannerTest {
    private static final PackageId LIB = new PackageId("com.example", "lib");

    private final ProjectPlatformMetadataPlanner planner =
            new ProjectPlatformMetadataPlanner(new CoordinateParser());

    @Test
    void plansManagedJarVersionsFromProjectPlatforms() {
        Map<PackageId, ManagedVersion> versions = planner.managedVersions(
                config(),
                coordinate -> platformPom(List.of(
                        dependency("com.example", "lib", "${lib.version}", Optional.empty(), Optional.empty(), Optional.empty()),
                        dependency("com.example", "tests", "1.0.0", Optional.empty(), Optional.empty(), Optional.of("tests")),
                        dependency("com.example", "missing-version", null, Optional.empty(), Optional.empty(), Optional.empty()),
                        dependency("com.example", "properties-artifact", "1.0.0", Optional.empty(), Optional.of("properties"), Optional.empty()))));

        assertEquals("2.0.0", versions.get(LIB).version());
        assertEquals("com.example:platform:1.0.0", versions.get(LIB).platform());
        assertTrue(versions.keySet().stream().noneMatch(packageId -> packageId.artifactId().equals("tests")));
        assertTrue(versions.keySet().stream().noneMatch(packageId -> packageId.artifactId().equals("missing-version")));
        assertTrue(versions.keySet().stream().noneMatch(packageId -> packageId.artifactId().equals("properties-artifact")));
    }

    @Test
    void plansPropertiesArtifactsAsQuarkusDeploymentRequests() {
        List<DependencyRequest> requests = planner.propertiesRequests(
                config(),
                coordinate -> platformPom(List.of(
                        dependency("com.example", "lib", "2.0.0", Optional.empty(), Optional.empty(), Optional.empty()),
                        dependency("io.quarkus", "quarkus-platform-properties", "${platform.version}", Optional.empty(), Optional.of("properties"), Optional.empty()))));

        assertEquals(1, requests.size());
        DependencyRequest request = requests.getFirst();
        assertEquals(new PackageId("io.quarkus", "quarkus-platform-properties"), request.packageId());
        assertEquals("3.33.0", request.requestedVersion());
        assertEquals(DependencyScope.QUARKUS_DEPLOYMENT, request.scope());
        assertEquals(RequestOrigin.TRANSITIVE, request.origin());
        assertTrue(request.artifactDescriptor().isPresent());
        assertEquals("properties", request.artifactDescriptor().orElseThrow().extension());
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [platforms]
                "com.example:platform" = "1.0.0"
                """);
    }

    private static EffectiveRawPom platformPom(List<RawPomDependency> dependencyManagement) {
        RawPom rawPom = new RawPom(
                Optional.of("com.example"),
                "platform",
                Optional.of("1.0.0"),
                "pom",
                Optional.empty(),
                Optional.empty(),
                Map.of(
                        "lib.version", "2.0.0",
                        "platform.version", "3.33.0"),
                dependencyManagement,
                List.of());
        return new EffectiveRawPom(
                rawPom,
                List.of(),
                "com.example",
                "1.0.0",
                rawPom.properties(),
                dependencyManagement);
    }

    private static RawPomDependency dependency(
            String groupId,
            String artifactId,
            String version,
            Optional<String> scope,
            Optional<String> type,
            Optional<String> classifier) {
        return new RawPomDependency(
                groupId,
                artifactId,
                Optional.ofNullable(version),
                scope,
                type,
                classifier,
                false,
                List.of());
    }
}
