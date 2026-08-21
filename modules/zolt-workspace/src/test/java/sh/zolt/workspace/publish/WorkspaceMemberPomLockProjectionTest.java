package sh.zolt.workspace.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.publish.PublishException;
import sh.zolt.publish.PublishPomGenerator;
import sh.zolt.toml.ZoltTomlParser;
import org.junit.jupiter.api.Test;

final class WorkspaceMemberPomLockProjectionTest {
    private final WorkspaceMemberPomLockProjection projection = new WorkspaceMemberPomLockProjection();

    @Test
    void filtersExactMemberAndPublishesFromAuthoredLanes() {
        ProjectConfig config = publishedLaneConfig();
        List<LockPackage> packages = List.of(
                external("org.example", "api", "1", DependencyScope.COMPILE, "apps/http"),
                external("org.example", "implementation", "2", DependencyScope.COMPILE, "apps/http"),
                external("org.example", "runtime", "3", DependencyScope.RUNTIME, "apps/http"),
                external("org.example", "provided", "4", DependencyScope.PROVIDED, "apps/http"),
                external("org.example", "dev", "5", DependencyScope.DEV, "apps/http"),
                external("org.example", "test", "6", DependencyScope.TEST, "apps/http"),
                external("org.example", "processor", "7", DependencyScope.PROCESSOR, "apps/http"),
                external("org.example", "test-processor", "8", DependencyScope.TEST_PROCESSOR, "apps/http"),
                external("org.example", "sibling", "6", DependencyScope.COMPILE, "apps/worker"));
        ZoltLockfile aggregate = lockfile(
                packages,
                List.of(
                        root("apps/http", "api", "1", DependencyLane.API, DependencyScope.COMPILE),
                        root("apps/http", "implementation", "2", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("apps/http", "runtime", "3", DependencyLane.RUNTIME, DependencyScope.RUNTIME),
                        root("apps/http", "provided", "4", DependencyLane.PROVIDED, DependencyScope.PROVIDED),
                        root("apps/http", "dev", "5", DependencyLane.DEV, DependencyScope.DEV),
                        root("apps/http", "test", "6", DependencyLane.TEST, DependencyScope.TEST),
                        root("apps/http", "processor", "7", DependencyLane.PROCESSOR, DependencyScope.PROCESSOR),
                        root("apps/http", "test-processor", "8", DependencyLane.TEST_PROCESSOR, DependencyScope.TEST_PROCESSOR),
                        root("apps/worker", "sibling", "6", DependencyLane.API, DependencyScope.COMPILE)));

        ZoltLockfile projected = projection.project("apps/http", config, aggregate);
        String pom = new PublishPomGenerator().generate(config, projected);

        assertEquals(
                List.of(
                        DependencyLane.API,
                        DependencyLane.IMPLEMENTATION,
                        DependencyLane.RUNTIME,
                        DependencyLane.PROVIDED),
                projected.dependencyRoots().stream().map(LockDependencyRoot::lane).toList());
        assertTrue(projected.dependencyRoots().stream().allMatch(root -> root.member().equals(".")));
        assertEquals(4, projected.packages().size());
        assertTrue(projected.packages().stream().allMatch(lockPackage -> lockPackage.members().isEmpty()));
        assertFalse(pom.contains("<artifactId>dev</artifactId>"));
        assertFalse(pom.contains("<artifactId>test</artifactId>"));
        assertFalse(pom.contains("<artifactId>processor</artifactId>"));
        assertFalse(pom.contains("<artifactId>test-processor</artifactId>"));
        assertFalse(pom.contains("<artifactId>sibling</artifactId>"));
        assertDependencyScope(pom, "api", null);
        assertDependencyScope(pom, "implementation", "runtime");
        assertDependencyScope(pom, "runtime", "runtime");
        assertDependencyScope(pom, "provided", "provided");
    }

    @Test
    void retainsPublishOnlyRootWithoutForgingAPackageNode() {
        String coordinate = "org.example:metadata-helper";
        DependencyMetadata metadata = new DependencyMetadata(
                "dependencies",
                coordinate,
                "9.4.0",
                false,
                null,
                true,
                true,
                List.of(new DependencyExclusionSpec("org.legacy", "bridge")));
        ProjectConfig config = config(Map.of(DependencyMetadata.key("dependencies", coordinate), metadata));
        LockDependencyRoot publishOnly = new LockDependencyRoot(
                "apps/http",
                new PackageId("org.example", "metadata-helper"),
                "9.4.0",
                null,
                DependencyLane.IMPLEMENTATION,
                Optional.empty(),
                true,
                true);
        ZoltLockfile aggregate = lockfile(List.of(), List.of(publishOnly));

        ZoltLockfile projected = projection.project("apps/http", config, aggregate);
        String pom = new PublishPomGenerator().generate(config, projected);

        assertTrue(projected.packages().isEmpty());
        assertEquals(1, projected.dependencyRoots().size());
        assertTrue(projected.dependencyRoots().getFirst().publishOnly());
        assertTrue(projected.dependencyRoots().getFirst().optional());
        assertTrue(pom.contains("<version>9.4.0</version>"));
        assertDependencyScope(pom, "metadata-helper", "runtime");
        assertTrue(pom.contains("<optional>true</optional>"));
        assertTrue(pom.contains("<artifactId>bridge</artifactId>"));
    }

    @Test
    void projectsTheExactLockedVariantRatherThanASiblingVariant() {
        LockArtifactVariant linux = new LockArtifactVariant("jar", Optional.of("linux-x86_64"));
        LockArtifactVariant osx = new LockArtifactVariant("jar", Optional.of("osx-aarch_64"));
        String artifact = "native-transport";
        LockPackage sibling = variantPackage(artifact, "1", linux, "apps/worker");
        LockPackage selected = variantPackage(artifact, "2", osx, "apps/http");
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "http"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [dependencies]
                "org.example:native-transport" = { version = "2", classifier = "osx-aarch_64" }
                """);
        ZoltLockfile aggregate = lockfile(
                List.of(sibling, selected),
                List.of(
                        root("apps/worker", artifact, "1", linux, DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("apps/http", artifact, "2", osx, DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)));

        ZoltLockfile projected = projection.project("apps/http", config, aggregate);
        String pom = new PublishPomGenerator().generate(config, projected);

        assertEquals(1, projected.packages().size());
        assertEquals(selected.packageId(), projected.packages().getFirst().packageId());
        assertEquals(selected.version(), projected.packages().getFirst().version());
        assertEquals(LockArtifactVariant.of(selected), LockArtifactVariant.of(projected.packages().getFirst()));
        assertTrue(projected.packages().getFirst().members().isEmpty());
        assertTrue(pom.contains("<version>2</version>"));
        assertTrue(pom.contains("<classifier>osx-aarch_64</classifier>"));
        assertFalse(pom.contains("linux-x86_64"));
    }

    @Test
    void rejectsPreV7AndMissingMemberRootCoverage() {
        PublishException old = assertThrows(
                PublishException.class,
                () -> projection.project(
                        "apps/http", config(Map.of()), new ZoltLockfile(6, List.of(), List.of())));
        assertTrue(old.getMessage().contains("requires zolt.lock version 7"));

        ProjectConfig declared = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("http", "1.0.0", "com.acme", "21", Optional.empty()),
                Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                Map.of("org.example:missing", "1"),
                Map.of(),
                BuildSettings.defaults());
        PublishException missing = assertThrows(
                PublishException.class,
                () -> projection.project(
                        "apps/http", declared, lockfile(List.of(), List.of())));
        assertTrue(missing.getMessage().contains("implementation:org.example:missing:jar"));
    }

    private static void assertDependencyScope(String pom, String artifact, String scope) {
        int start = pom.indexOf("<artifactId>" + artifact + "</artifactId>");
        int end = pom.indexOf("</dependency>", start);
        String dependency = pom.substring(start, end);
        if (scope == null) {
            assertFalse(dependency.contains("<scope>"));
        } else {
            assertTrue(dependency.contains("<scope>" + scope + "</scope>"));
        }
    }

    private static ProjectConfig config(Map<String, DependencyMetadata> metadata) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("http", "1.0.0", "com.acme", "21", Optional.empty()),
                        Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withDependencyMetadata(metadata);
    }

    private static ProjectConfig publishedLaneConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "http"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [api.dependencies]
                "org.example:api" = "1"

                [dependencies]
                "org.example:implementation" = "2"

                [runtime.dependencies]
                "org.example:runtime" = "3"

                [provided.dependencies]
                "org.example:provided" = "4"
                """);
    }

    private static LockDependencyRoot root(
            String member,
            String artifact,
            String version,
            DependencyLane lane,
            DependencyScope scope) {
        return root(member, artifact, version, LockArtifactVariant.defaultVariant(), lane, scope);
    }

    private static LockDependencyRoot root(
            String member,
            String artifact,
            String version,
            LockArtifactVariant variant,
            DependencyLane lane,
            DependencyScope scope) {
        return new LockDependencyRoot(
                member,
                new PackageId("org.example", artifact),
                version,
                variant,
                lane,
                Optional.of(scope),
                false,
                false);
    }

    private static LockPackage external(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            String member) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                ProjectConfig.MAVEN_CENTRAL,
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(member),
                List.of());
    }

    private static LockPackage variantPackage(
            String artifact,
            String version,
            LockArtifactVariant variant,
            String member) {
        String path = "org/example/" + artifact + "/" + version + "/" + artifact + "-" + version
                + "-" + variant.classifier().orElseThrow() + ".jar";
        return new LockPackage(
                new PackageId("org.example", artifact),
                version,
                ProjectConfig.MAVEN_CENTRAL,
                DependencyScope.COMPILE,
                true,
                Optional.of(path),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(member),
                List.of());
    }

    private static ZoltLockfile lockfile(
            List<LockPackage> packages,
            List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                7, Optional.empty(), Optional.empty(), List.of(), packages, List.of(), List.of(), List.of(), roots);
    }
}
