package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class ResolveServiceDependencyRootTest extends ResolveServiceTestSupport {
    @Test
    void locksAllAuthoredLanesAndKeepsPublishOnlyOutOfThePackageGraph() {
        List<String> artifacts = List.of("api", "implementation", "runtime", "provided", "dev", "test", "processor", "test-processor");
        artifacts.forEach(artifact -> addArtifact(
                "com.example", artifact, "1.0.0", simplePom("com.example", artifact, "1.0.0")));
        addJUnitConsoleArtifact("1.11.4");
        Path project = tempDir.resolve("all-root-lanes");
        createDirectory(project);

        ResolveResult result = resolveService.resolve(project, allLanesConfig(), tempDir.resolve("all-root-lanes-cache"));
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        Map<DependencyLane, LockDependencyRoot> roots = lockfile.dependencyRoots().stream()
                .filter(root -> !root.publishOnly())
                .collect(Collectors.toMap(LockDependencyRoot::lane, root -> root));

        assertEquals(9, result.resolvedCount());
        assertEquals(9, lockfile.dependencyRoots().size());
        assertEquals(DependencyScope.COMPILE, roots.get(DependencyLane.API).resolvedScope().orElseThrow());
        assertEquals(DependencyScope.COMPILE, roots.get(DependencyLane.IMPLEMENTATION).resolvedScope().orElseThrow());
        assertTrue(roots.get(DependencyLane.API).optional());
        assertEquals(DependencyScope.RUNTIME, roots.get(DependencyLane.RUNTIME).resolvedScope().orElseThrow());
        assertEquals(DependencyScope.PROCESSOR, roots.get(DependencyLane.PROCESSOR).resolvedScope().orElseThrow());
        LockDependencyRoot publishOnly = lockfile.dependencyRoots().stream()
                .filter(LockDependencyRoot::publishOnly)
                .findFirst()
                .orElseThrow();
        assertEquals("com.example:published", publishOnly.packageId().toString());
        assertEquals("2.0.0", publishOnly.version());
        assertTrue(publishOnly.resolvedScope().isEmpty());
        assertFalse(lockfile.packages().stream().anyMatch(lockPackage -> lockPackage.packageId().equals(publishOnly.packageId())));
    }

    @Test
    void managedRootLocksThePlatformSelectedVersion() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId><artifactId>platform</artifactId><version>1.0.0</version>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>com.example</groupId><artifactId>managed</artifactId><version>3.2.1</version>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """);
        addArtifact("com.example", "managed", "3.2.1", simplePom("com.example", "managed", "3.2.1"));
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [platforms]
                "com.example:platform" = "1.0.0"

                [dependencies]
                "com.example:managed" = { managed = true }
                """.formatted(baseUri));
        Path project = tempDir.resolve("managed-root");
        createDirectory(project);

        ResolveResult result = resolveService.resolve(project, config, tempDir.resolve("managed-root-cache"));

        assertEquals("3.2.1", lockfileReader.read(result.lockfilePath())
                .dependencyRoots().getFirst().version());
    }

    @Test
    void publishOnlyVersionRefLocksTheEffectiveAliasWithoutCreatingAPackage() {
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [versions]
                published = "4.3.2"

                [dependencies]
                "com.example:published" = { versionRef = "published", publishOnly = true }
                """);
        Path project = tempDir.resolve("publish-only-version-ref");
        createDirectory(project);

        ResolveResult result = resolveService.resolve(
                project, config, tempDir.resolve("publish-only-version-ref-cache"));
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());

        assertEquals(0, result.resolvedCount());
        assertEquals(1, lockfile.dependencyRoots().size());
        assertEquals("4.3.2", lockfile.dependencyRoots().getFirst().version());
        assertTrue(lockfile.dependencyRoots().getFirst().publishOnly());
        assertTrue(lockfile.packages().isEmpty());
    }

    private ProjectConfig allLanesConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies.api]
                "com.example:api" = { version = "1.0.0", optional = true }

                [dependencies]
                "com.example:implementation" = "1.0.0"
                "com.example:published" = { version = "2.0.0", publishOnly = true }

                [dependencies.runtime]
                "com.example:runtime" = "1.0.0"

                [dependencies.provided]
                "com.example:provided" = "1.0.0"

                [dependencies.dev]
                "com.example:dev" = "1.0.0"

                [dependencies.test]
                "com.example:test" = "1.0.0"

                [dependencies.processor]
                "com.example:processor" = "1.0.0"

                [dependencies.test-processor]
                "com.example:test-processor" = "1.0.0"
                """.formatted(baseUri));
    }
}
