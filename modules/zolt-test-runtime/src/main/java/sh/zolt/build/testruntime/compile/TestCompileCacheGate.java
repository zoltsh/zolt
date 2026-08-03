package sh.zolt.build.testruntime.compile;

import sh.zolt.build.cache.BuildCacheJdkIdentity;
import sh.zolt.build.cache.BuildCacheKey;
import sh.zolt.build.cache.BuildCacheModulePolicy;
import sh.zolt.build.cache.BuildCacheScope;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class TestCompileCacheGate {
    private final BuildCacheService cache;
    private final BuildFingerprintService fingerprints;

    TestCompileCacheGate(
            BuildCacheService cache,
            BuildFingerprintService fingerprints) {
        this.cache = cache;
        this.fingerprints = fingerprints;
    }

    BuildCacheKey key(
            boolean compileSkipped,
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            List<GeneratedSourceProducerFingerprint> generatedProducerFingerprints,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus) {
        if (compileSkipped
                || !cache.enabled()
                || Files.exists(IncrementalCompileState.testStatePath(outputDirectory))
                || !BuildCacheModulePolicy.cacheable(config)) {
            return null;
        }
        String inputsSha = fingerprints.testInputsFingerprintSha256(
                projectDirectory,
                config,
                lockfilePath,
                sources,
                generatedProducerFingerprints,
                compileClasspath,
                processorClasspath,
                outputDirectory,
                generatedSourcesDirectory);
        return BuildCacheKey.of(
                BuildCacheScope.TEST,
                inputsSha,
                BuildCacheJdkIdentity.of(jdkStatus));
    }
}
