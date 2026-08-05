package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import sh.zolt.resolve.materialization.session.WorkspaceResolutionSession;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private String lockfile(ResolveOutput output) {
        return lockfileWriter.write(output.lockfile());
    }
}
