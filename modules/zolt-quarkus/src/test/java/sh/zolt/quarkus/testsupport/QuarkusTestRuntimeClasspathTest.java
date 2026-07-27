package sh.zolt.quarkus.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.home.UserGlobalDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusTestRuntimeClasspathTest {
    @TempDir
    Path tempDir;

    @Test
    void fallsBackToLaterCacheRootWhenRepoCacheIsMissing() throws IOException {
        Path missingRepoCache = tempDir.resolve("worktree/.zolt/cache");
        Path seededCache = tempDir.resolve("seeded-cache");
        Path jar = createJar(seededCache, "io/quarkus/quarkus-core/3.0.0/quarkus-core-3.0.0.jar");

        List<Path> jars = QuarkusTestRuntimeClasspath.existingCacheJars(
                List.of(missingRepoCache, seededCache),
                List.of("io/quarkus/quarkus-core/3.0.0/quarkus-core-3.0.0.jar"));

        assertEquals(List.of(jar), jars);
    }

    @Test
    void prefersEarlierCacheRootWhenJarExistsInSeveral() throws IOException {
        Path overrideCache = tempDir.resolve("override-cache");
        Path repoCache = tempDir.resolve("repo/.zolt/cache");
        String relativeJar = "org/jboss/jandex/3.1.6/jandex-3.1.6.jar";
        Path overrideJar = createJar(overrideCache, relativeJar);
        createJar(repoCache, relativeJar);

        List<Path> jars = QuarkusTestRuntimeClasspath.existingCacheJars(
                List.of(overrideCache, repoCache), List.of(relativeJar));

        assertEquals(List.of(overrideJar), jars);
    }

    @Test
    void skipsJarsAbsentFromEveryCacheRoot() {
        List<Path> jars = QuarkusTestRuntimeClasspath.existingCacheJars(
                List.of(tempDir.resolve("empty-cache")),
                List.of("io/quarkus/quarkus-core/3.0.0/quarkus-core-3.0.0.jar"));

        assertEquals(List.of(), jars);
    }

    @Test
    void candidateRootsResolveRelativeOverrideAgainstRepoRoot() {
        Path repoRoot = tempDir.resolve("repo");

        List<Path> cacheRoots = QuarkusTestRuntimeClasspath.candidateCacheRoots(repoRoot, ".zolt/cache-copy");

        assertEquals(
                List.of(
                        repoRoot.resolve(".zolt/cache-copy"),
                        repoRoot.resolve(".zolt/cache"),
                        UserGlobalDirectory.artifactCache()),
                cacheRoots);
    }

    @Test
    void candidateRootsKeepAbsoluteOverride() {
        Path repoRoot = tempDir.resolve("repo");
        Path override = tempDir.resolve("shared-cache");

        List<Path> cacheRoots = QuarkusTestRuntimeClasspath.candidateCacheRoots(repoRoot, override.toString());

        assertEquals(
                List.of(override, repoRoot.resolve(".zolt/cache"), UserGlobalDirectory.artifactCache()),
                cacheRoots);
    }

    @Test
    void candidateRootsOmitBlankOverride() {
        Path repoRoot = tempDir.resolve("repo");

        List<Path> cacheRoots = QuarkusTestRuntimeClasspath.candidateCacheRoots(repoRoot, "  ");

        assertEquals(
                List.of(repoRoot.resolve(".zolt/cache"), UserGlobalDirectory.artifactCache()), cacheRoots);
    }

    private static Path createJar(Path cacheRoot, String relativeJar) throws IOException {
        Path jar = cacheRoot.resolve(relativeJar);
        Files.createDirectories(jar.getParent());
        Files.createFile(jar);
        return jar;
    }
}
