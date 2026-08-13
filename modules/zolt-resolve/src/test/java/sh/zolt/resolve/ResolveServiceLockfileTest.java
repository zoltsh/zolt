package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cache.ArtifactCacheException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResolveServiceLockfileTest extends ResolveServiceTestSupport {
    @Test
    void lockedResolveSucceedsWhenLockfileMatches() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        resolveService.resolve(projectDir, config(), cacheRoot);
        String existingLockfile = Files.readString(projectDir.resolve("zolt.lock"));

        ResolveResult result = resolveService.resolve(projectDir, config(), cacheRoot, true);

        assertEquals(2, result.resolvedCount());
        assertEquals(existingLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void lockedResolveFailsWhenLockfileIsMissing() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, config(), cacheRoot, true));

        assertTrue(exception.getMessage().contains("Locked resolve requires zolt.lock"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve` to create it"));
    }

    @Test
    void lockedResolveFailsWhenLockfileWouldChange() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        resolveService.resolve(projectDir, config(), cacheRoot);
        String existingLockfile = Files.readString(projectDir.resolve("zolt.lock"));
        addArtifact("com.example", "extra", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>extra</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, configWithDependencies(Map.of(
                        "com.example:app", "1.0.0",
                        "com.example:extra", "1.0.0")), cacheRoot, true));

        assertTrue(exception.getMessage().contains("zolt.lock is out of date"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve` to refresh it"));
        assertEquals(existingLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void lockedResolveFailsWhenPlatformVersionRefEdgeChangesWithoutConcreteVersionChange() throws IOException {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path projectDir = tempDir.resolve("project-platform-alias-lock");
        Path cacheRoot = tempDir.resolve("cache-platform-alias-lock");
        createDirectory(projectDir);
        resolveService.resolve(projectDir, platformVersionRefConfig("platform-one"), cacheRoot);
        String existingLockfile = Files.readString(projectDir.resolve("zolt.lock"));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, platformVersionRefConfig("platform-two"), cacheRoot, true));

        assertTrue(existingLockfile.contains("aliasFingerprint = \"sha256:"));
        assertTrue(exception.getMessage().contains("zolt.lock is out of date"));
        assertEquals(existingLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void lockedResolveFailsWhenRepositoryInputChangesWithoutGraphChange() throws IOException {
        Path projectDir = tempDir.resolve("project-repository-lock");
        Path cacheRoot = tempDir.resolve("cache-repository-lock");
        createDirectory(projectDir);
        resolveService.resolve(projectDir, config(), cacheRoot);
        String existingLockfile = Files.readString(projectDir.resolve("zolt.lock"));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, configWithRepository(baseUri + "./"), cacheRoot, true));

        assertTrue(existingLockfile.contains("projectResolutionFingerprint = \"sha256:"));
        assertTrue(exception.getMessage().contains("zolt.lock is out of date"));
        assertTrue(exception.getMessage().contains("Changed inputs: repositories."));
        assertEquals(existingLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void lockedResolveFailsWhenOpenApiToolVersionRefEdgeChangesWithoutConcreteVersionChange() throws IOException {
        addArtifact("org.openapitools", "openapi-generator-cli", "7.11.0", """
                <project>
                  <groupId>org.openapitools</groupId>
                  <artifactId>openapi-generator-cli</artifactId>
                  <version>7.11.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project-openapi-tool-alias-lock");
        Path cacheRoot = tempDir.resolve("cache-openapi-tool-alias-lock");
        createDirectory(projectDir);
        resolveService.resolve(projectDir, openApiVersionRefConfig("openapi-one"), cacheRoot);
        String existingLockfile = Files.readString(projectDir.resolve("zolt.lock"));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, openApiVersionRefConfig("openapi-two"), cacheRoot, true));

        assertTrue(existingLockfile.contains("aliasFingerprint = \"sha256:"));
        assertTrue(exception.getMessage().contains("zolt.lock is out of date"));
        assertEquals(existingLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void offlineResolveUsesCachedArtifactsWithoutFetching() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        resolveService.resolve(projectDir, config(), cacheRoot);
        responses.clear();
        resetRequestCounts();

        ResolveResult result = resolveService.resolve(projectDir, config(), cacheRoot, false, true);

        assertEquals(2, result.resolvedCount());
        assertEquals(0, result.downloadCount());
        assertEquals(0, totalRequests.get());
        assertTrue(requestCounts.isEmpty());
    }

    @Test
    void offlineResolveFailsClearlyWhenArtifactIsMissingFromCache() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> resolveService.resolve(projectDir, config(), cacheRoot, false, true));

        assertTrue(exception.getMessage().contains("Offline mode requires cached POM"));
        assertTrue(exception.getMessage().contains("Run the command without --offline"));
    }
}
