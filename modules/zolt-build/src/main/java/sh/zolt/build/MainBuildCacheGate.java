package sh.zolt.build;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.cache.BuildCacheKey;
import sh.zolt.build.cache.BuildCacheModulePolicy;
import sh.zolt.build.cache.BuildCacheRestoreResult;
import sh.zolt.build.cache.BuildCacheScope;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The main-compile-scope build-output cache discipline: decide whether a cold, hermetic module may
 * restore compiled classes instead of running javac, and store them back after a real compile. Owns the
 * cache service and derives the content half of the cache key from {@link BuildFingerprintService}, so
 * {@link BuildService} delegates restore/store instead of inlining the policy.
 */
final class MainBuildCacheGate {
    private final BuildCacheService buildCacheService;
    private final BuildFingerprintService buildFingerprintService;

    MainBuildCacheGate(BuildCacheService buildCacheService, BuildFingerprintService buildFingerprintService) {
        this.buildCacheService = buildCacheService;
        this.buildFingerprintService = buildFingerprintService;
    }

    Attempt attemptRestore(
            boolean compileSkipped,
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            String compilerIdentity) {
        if (compileSkipped || !buildCacheService.enabled()) {
            return Attempt.inactive();
        }
        if (Files.exists(IncrementalCompileState.mainStatePath(outputDirectory))) {
            // Warm incremental state present: the incremental compiler is already the fast path. The
            // build cache serves cold/clean/CI builds; consulting it under warm state only adds overhead.
            return Attempt.inactive();
        }
        if (!BuildCacheModulePolicy.cacheable(config)) {
            return Attempt.uncacheable();
        }
        String inputsSha = buildFingerprintService.mainInputsFingerprintSha256(
                projectDirectory, config, compilerIdentity, lockfilePath, sources,
                classpaths, outputDirectory, generatedSourcesDirectory);
        BuildCacheKey key = BuildCacheKey.of(BuildCacheScope.MAIN, inputsSha, compilerIdentity);
        return Attempt.active(key, buildCacheService.restore(key, outputDirectory));
    }

    String store(Attempt attempt, Path outputDirectory) {
        if (attempt.key().isEmpty()) {
            return attempt.moduleTainted() ? "uncacheable" : "";
        }
        buildCacheService.store(attempt.key().orElseThrow(), outputDirectory);
        return "stored";
    }

    record Attempt(
            Optional<BuildCacheKey> key,
            BuildCacheRestoreResult restore,
            boolean moduleTainted) {
        boolean restored() {
            return restore.restored();
        }

        static Attempt inactive() {
            return new Attempt(Optional.empty(), BuildCacheRestoreResult.miss(), false);
        }

        static Attempt uncacheable() {
            return new Attempt(Optional.empty(), BuildCacheRestoreResult.miss(), true);
        }

        static Attempt active(BuildCacheKey key, BuildCacheRestoreResult restore) {
            return new Attempt(Optional.of(key), restore, false);
        }
    }
}
