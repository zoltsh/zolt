package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResolveServicePolicyTest extends ResolveServiceTestSupport {
    @Test
    void directDependencyMetadataExclusionsReachResolverRequests() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = config().withDependencyMetadata(Map.of(
                DependencyMetadata.key("dependencies", "com.example:app"),
                new DependencyMetadata(
                        "dependencies",
                        "com.example:app",
                        "1.0.0",
                        false,
                        null,
                        false,
                        false,
                        List.of(new DependencyExclusionSpec("com.example", "lib")))));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());

        assertEquals(1, result.resolvedCount());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app"))));
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "lib"))));
        assertTrue(lockfile.policyEffects().stream().anyMatch(effect ->
                "edge-exclusion".equals(effect.kind())
                        && effect.packageId().equals(new PackageId("com.example", "lib"))
                        && effect.requestedVersion().orElseThrow().equals("1.0.0")
                        && effect.source().orElseThrow().equals("com.example:app:1.0.0")
                        && effect.policy().contains("dependency edge exclusion")));
    }

    /**
     * The resolver's wildcard-exclusion gate fires before any repository request. The final manifest
     * language cannot spell a wildcard exclusion at all — {@code exclude} takes exact
     * {@code group:artifact} coordinates (design §9.5) — so the guard is defense in depth for a
     * {@link ProjectConfig} built programmatically, and it must still cost no network access.
     */
    @Test
    void wildcardDirectDependencyExclusionsFailBeforeNetworkAccess() {
        Path projectDir = tempDir.resolve("project-wildcard-exclusion");
        Path cacheRoot = tempDir.resolve("cache-wildcard-exclusion");
        createDirectory(projectDir);
        ProjectConfig config = config().withDependencyMetadata(Map.of(
                DependencyMetadata.key("dependencies", "com.example:app"),
                new DependencyMetadata(
                        "dependencies",
                        "com.example:app",
                        "1.0.0",
                        false,
                        null,
                        false,
                        false,
                        List.of(new DependencyExclusionSpec("*", "legacy-logging")))));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, config, cacheRoot));

        assertTrue(exception.getMessage().contains("Wildcard dependency exclusions are not supported"));
        assertTrue(exception.getMessage().contains("*:legacy-logging"));
        assertTrue(exception.getMessage().contains("Replace it with explicit group and artifact exclusions"));
        assertEquals(0, totalRequests.get());
    }

    @Test
    void authoredWildcardExclusionIsRejectedAtTheManifestBoundary() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> new ManifestProjectConfigLoader().load("""
                        [project]
                        name = "demo"
                        version = "0.1.0"
                        group = "com.example"
                        java = 21

                        [dependencies]
                        "com.example:app" = { version = "1.0.0", exclude = ["*:legacy-logging"] }
                        """));

        assertTrue(failure.getMessage().contains("dependencies.com.example:app.exclude[0]"), failure.getMessage());
        assertTrue(failure.getMessage().contains("use exact `group:artifact` syntax"), failure.getMessage());
    }

    @Test
    void directVersionRefDependencyRecordsLockfilePolicy() {
        Path projectDir = tempDir.resolve("project-version-ref");
        Path cacheRoot = tempDir.resolve("cache-version-ref");
        createDirectory(projectDir);
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [versions]
                app = "1.0.0"

                [dependencies]
                "com.example:app" = { versionRef = "app" }
                """.formatted(baseUri));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage app = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "app")))
                .filter(LockPackage::direct)
                .findFirst()
                .orElseThrow();

        assertEquals("1.0.0", app.version());
        assertTrue(app.policies().contains(
                "version-ref: com.example:app -> 1.0.0 from [versions].app"));
    }

    @Test
    void unmanagedDirectDependencyFailsClearly() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>other</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, platformConfig(), cacheRoot));

        assertTrue(exception.getMessage().contains("Dependency com.example:app in [dependencies]"));
        assertTrue(exception.getMessage().contains("uses a platform-managed version"));
    }

}
