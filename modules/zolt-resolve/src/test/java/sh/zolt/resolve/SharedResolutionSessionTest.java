package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import sh.zolt.resolve.materialization.session.WorkspaceResolutionSession;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The sharing contract: projects that resolve over one session must produce exactly the lockfiles
 * they produce alone, deriving the shared metadata once — and must not share it across a repository
 * configuration that could have served different bytes.
 */
final class SharedResolutionSessionTest extends ResolveServiceTestSupport {
    private final ZoltLockfileWriter lockfileWriter = new ZoltLockfileWriter();

    @Test
    void producesTheSameLockfilesAsResolvingEachProjectAlone() {
        Path cacheRoot = tempDir.resolve("cache");
        ProjectConfig first = config();
        ProjectConfig second = configWithDependencies(Map.of("com.example:lib", "1.0.0"));

        String firstAlone = lockfile(resolveService.resolveLockfile(first, cacheRoot, ResolveOptions.defaults()));
        String secondAlone = lockfile(resolveService.resolveLockfile(second, cacheRoot, ResolveOptions.defaults()));

        Path sharedCacheRoot = tempDir.resolve("shared-cache");
        WorkspaceResolutionSession session =
                resolveService.newResolutionSession(sharedCacheRoot, ResolveOptions.defaults());
        String firstShared = lockfile(
                resolveService.resolveLockfile(first, sharedCacheRoot, ResolveOptions.defaults(), session));
        String secondShared = lockfile(
                resolveService.resolveLockfile(second, sharedCacheRoot, ResolveOptions.defaults(), session));

        assertEquals(firstAlone, firstShared);
        assertEquals(secondAlone, secondShared);
    }

    @Test
    void parsesEachPomOnceAcrossProjectsThatShareRepositories() {
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceResolutionSession session =
                resolveService.newResolutionSession(cacheRoot, ResolveOptions.defaults());

        ResolveOutput first =
                resolveService.resolveLockfile(config(), cacheRoot, ResolveOptions.defaults(), session);
        ResolveOutput second =
                resolveService.resolveLockfile(config(), cacheRoot, ResolveOptions.defaults(), session);

        assertEquals(2, first.metrics().rawPomCacheMisses());
        assertEquals(0, second.metrics().rawPomCacheMisses());
        assertEquals(0, second.metrics().effectivePomCacheMisses());
        assertEquals(0, second.downloadCount());
    }

    @Test
    void keepsMetadataApartWhenAProjectDeclaresAnExtraRepository() {
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceResolutionSession session =
                resolveService.newResolutionSession(cacheRoot, ResolveOptions.defaults());
        ProjectConfig sharedRepositories = config();
        ProjectConfig extraRepository = configWithExtraRepository();

        ResolveOutput first =
                resolveService.resolveLockfile(sharedRepositories, cacheRoot, ResolveOptions.defaults(), session);
        ResolveOutput second =
                resolveService.resolveLockfile(extraRepository, cacheRoot, ResolveOptions.defaults(), session);

        assertEquals(2, first.metrics().rawPomCacheMisses());
        assertEquals(2, second.metrics().rawPomCacheMisses());
        assertEquals(first.lockfile().packages(), second.lockfile().packages());
    }

    @Test
    void isolatesDivergentPomAndJarBytesAcrossMembersInvocationsAndRepositoryOrderings() {
        addRepositoryArtifact("repo-a", "lib-a", "jar-a");
        addRepositoryArtifact("repo-b", "lib-b", "jar-b");
        ProjectConfig privateFirst = repositoryOrdering(
                ordered("a-private", repositoryUrl("repo-a"), "b-public", repositoryUrl("repo-b")));
        ProjectConfig publicFirst = repositoryOrdering(
                ordered("a-public", repositoryUrl("repo-b"), "b-private", repositoryUrl("repo-a")));
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceResolutionSession session =
                resolveService.newResolutionSession(cacheRoot, ResolveOptions.defaults());

        ResolveOutput first =
                resolveService.resolveLockfile(privateFirst, cacheRoot, ResolveOptions.defaults(), session);
        ResolveOutput second =
                resolveService.resolveLockfile(publicFirst, cacheRoot, ResolveOptions.defaults(), session);

        LockPackage firstApp = locked(first, "app");
        LockPackage secondApp = locked(second, "app");
        assertEquals("a-private", firstApp.source());
        assertEquals("a-public", secondApp.source());
        assertNotEquals(firstApp.jarSha256(), secondApp.jarSha256());
        assertNotEquals(firstApp.jar(), secondApp.jar());
        assertTrue(first.lockfile().packages().stream()
                .anyMatch(lockPackage -> lockPackage.packageId().artifactId().equals("lib-a")));
        assertTrue(second.lockfile().packages().stream()
                .anyMatch(lockPackage -> lockPackage.packageId().artifactId().equals("lib-b")));

        resetRequestCounts();
        WorkspaceResolutionSession offlineSession =
                resolveService.newResolutionSession(cacheRoot, ResolveOptions.offline(true));
        ResolveOutput cachedFirst = resolveService.resolveLockfile(
                privateFirst, cacheRoot, ResolveOptions.offline(true), offlineSession);
        ResolveOutput cachedSecond = resolveService.resolveLockfile(
                publicFirst, cacheRoot, ResolveOptions.offline(true), offlineSession);

        assertEquals(first.lockfile().packages(), cachedFirst.lockfile().packages());
        assertEquals(second.lockfile().packages(), cachedSecond.lockfile().packages());
        assertEquals(0, totalRequests.get());
    }

    @Test
    void rejectsAProjectResolvingAgainstAnotherCacheRoot() {
        WorkspaceResolutionSession session =
                resolveService.newResolutionSession(tempDir.resolve("cache"), ResolveOptions.defaults());

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolveLockfile(
                        config(), tempDir.resolve("other-cache"), ResolveOptions.defaults(), session));

        assertTrue(exception.getMessage().contains("Resolve each cache root in its own session."));
    }

    @Test
    void rejectsAProjectResolvingUnderAnotherMaterializationPolicy() {
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceResolutionSession session =
                resolveService.newResolutionSession(cacheRoot, ResolveOptions.defaults());

        ResolveException offline = assertThrows(
                ResolveException.class,
                () -> resolveService.resolveLockfile(config(), cacheRoot, ResolveOptions.offline(true), session));
        ResolveException overlay = assertThrows(
                ResolveException.class,
                () -> resolveService.resolveLockfile(
                        config(),
                        cacheRoot,
                        new ResolveOptions(
                                false,
                                List.of(RepositoryOverlay.mavenLocal(tempDir.resolve("m2"))),
                                false),
                        session));

        assertTrue(offline.getMessage().contains("artifact materialization"));
        assertTrue(overlay.getMessage().contains("repository-overlay combination in its own session"));
    }

    private ProjectConfig configWithExtraRepository() {
        return sh.zolt.project.ProjectConfigs.withDirectDependencies(
                new sh.zolt.project.ProjectMetadata(
                        "demo", "0.1.0", "com.example", "21", java.util.Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString(), "extra", baseUri + "extra/"),
                Map.of("com.example:app", "1.0.0"),
                Map.of(),
                sh.zolt.project.BuildSettings.defaults());
    }

    /**
     * Repository lookup order is authored policy (design §8.5) and the planner carries it verbatim, so
     * an ordering fixture has to state it — {@code Map.of} publishes an arbitrary iteration order.
     */
    private static Map<String, String> ordered(String firstId, String firstUrl, String secondId, String secondUrl) {
        Map<String, String> repositories = new java.util.LinkedHashMap<>();
        repositories.put(firstId, firstUrl);
        repositories.put(secondId, secondUrl);
        return repositories;
    }

    private ProjectConfig repositoryOrdering(Map<String, String> repositories) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                repositories,
                Map.of("com.example:app", "1.0.0"),
                Map.of(),
                BuildSettings.defaults());
    }

    private String repositoryUrl(String repository) {
        return baseUri.resolve("/" + repository + "/").toString();
    }

    private void addRepositoryArtifact(String repository, String transitiveArtifact, String jar) {
        String root = "/" + repository + "/com/example/app/1.0.0/app-1.0.0";
        responses.put(root + ".pom", ("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>%s</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """).formatted(transitiveArtifact).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        responses.put(root + ".jar", jar.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String transitiveRoot = "/" + repository + "/com/example/" + transitiveArtifact
                + "/1.0.0/" + transitiveArtifact + "-1.0.0";
        responses.put(transitiveRoot + ".pom", ("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                </project>
                """).formatted(transitiveArtifact).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        responses.put(
                transitiveRoot + ".jar",
                ("jar-" + transitiveArtifact).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static LockPackage locked(ResolveOutput output, String artifactId) {
        return output.lockfile().packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", artifactId)))
                .findFirst()
                .orElseThrow();
    }

    private String lockfile(ResolveOutput output) {
        return lockfileWriter.write(output.lockfile());
    }
}
