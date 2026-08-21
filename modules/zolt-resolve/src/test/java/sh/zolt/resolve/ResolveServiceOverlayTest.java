package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.repository.RepositoryAccessException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResolveServiceOverlayTest extends ResolveServiceTestSupport {
    @Test
    void mavenLocalOverlayTakesPrecedenceOverConfiguredRepositoriesAndRecordsSafeOrigin() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        Path mavenLocalRoot = tempDir.resolve("m2/repository");
        createDirectory(projectDir);
        writeLocalArtifact(mavenLocalRoot, "com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                </project>
                """, Map.of("local.txt", "local overlay wins\n"));

        ResolveResult result = resolveService.resolve(
                projectDir,
                config(),
                cacheRoot,
                false,
                new ResolveOptions(false, List.of(RepositoryOverlay.mavenLocal(mavenLocalRoot)), false));

        assertEquals(1, result.resolvedCount());
        assertEquals(0, requestCount("/maven2/com/example/app/1.0.0/app-1.0.0.pom"));
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage app = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "app")))
                .findFirst()
                .orElseThrow();
        assertEquals("local-overlay:maven-local", app.source());
        assertTrue(app.jar().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(app.jar().orElseThrow().endsWith("/app-1.0.0.jar"));
        assertTrue(app.pom().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(app.pom().orElseThrow().endsWith("/app-1.0.0.pom"));
        assertTrue(app.jar().orElseThrow().contains("app-1.0.0.jar"));
        assertTrue(app.jar().orElseThrow().indexOf(tempDir.toString()) < 0);
        assertTrue(Files.isRegularFile(cacheRoot.resolve(app.jar().orElseThrow())));
    }

    @Test
    void mavenLocalOverlayFallsBackToConfiguredRepositoriesWhenArtifactIsMissing() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        Path mavenLocalRoot = tempDir.resolve("empty-m2/repository");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                config(),
                cacheRoot,
                false,
                new ResolveOptions(false, List.of(RepositoryOverlay.mavenLocal(mavenLocalRoot)), false));

        assertEquals(2, result.resolvedCount());
        assertEquals(1, requestCount("/maven2/com/example/app/1.0.0/app-1.0.0.pom"));
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream()
                .allMatch(lockPackage -> "test".equals(lockPackage.source())));
    }

    @Test
    void lockedResolveCanRejectExistingLocalOverlayOrigins() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        Path mavenLocalRoot = tempDir.resolve("m2/repository");
        createDirectory(projectDir);
        writeLocalArtifact(mavenLocalRoot, "com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                </project>
                """, Map.of());
        resolveService.resolve(
                projectDir,
                config(),
                cacheRoot,
                false,
                new ResolveOptions(false, List.of(RepositoryOverlay.mavenLocal(mavenLocalRoot)), false));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(
                        projectDir,
                        config(),
                        cacheRoot,
                        true,
                        new ResolveOptions(false, List.of(), true)));

        assertTrue(exception.getMessage().contains("Local repository overlay artifacts are not allowed"));
        assertTrue(exception.getMessage().contains("refresh zolt.lock from configured repositories"));
    }

    @Test
    void credentialedRepositoryFailsBeforeNetworkWhenEnvironmentIsMissing() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                "company" = { url = "%s", credentials = "company-artifactory" }

                [credentials.company-artifactory]
                usernameEnv = "ZOLT_TEST_MISSING_REPOSITORY_USERNAME"
                passwordEnv = "ZOLT_TEST_MISSING_REPOSITORY_PASSWORD"

                [dependencies]
                "com.example:app" = "1.0.0"
                """.formatted(baseUri));

        RepositoryAccessException exception = assertThrows(
                RepositoryAccessException.class,
                () -> resolveService.resolve(projectDir, config, cacheRoot));

        assertTrue(exception.getMessage().contains("Repository `company` requires credentials `company-artifactory`"));
        assertTrue(exception.getMessage().contains("ZOLT_TEST_MISSING_REPOSITORY_USERNAME"));
        assertTrue(exception.getMessage().contains("ZOLT_TEST_MISSING_REPOSITORY_PASSWORD"));
        assertTrue(exception.getMessage().contains("Secret values are never written to zolt.lock or command output."));
        assertEquals(0, totalRequests.get());
    }

    @Test
    void resolveTriesRepositoriesInStableOrderUntilArtifactIsFound() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.empty]
                url = "%s"

                [repositories.test]
                url = "%s"

                [dependencies]
                "com.example:app" = "1.0.0"
                """.formatted(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/empty/",
                baseUri));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);

        assertEquals(2, result.resolvedCount());
        assertEquals(1, requestCount("/empty/com/example/app/1.0.0/app-1.0.0.pom"));
        assertEquals(1, requestCount("/maven2/com/example/app/1.0.0/app-1.0.0.pom"));
    }
}
