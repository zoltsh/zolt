package sh.zolt.build.testruntime.compile;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.build.BuildException;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.build.BuildService;
import sh.zolt.build.cache.BuildCacheKey;
import sh.zolt.build.cache.BuildCacheRestoreResult;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.compile.GroovyCompilerRunner;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.fingerprint.BuildFingerprintCheck;
import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.build.resources.ResourceCopyResult;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.generatedsource.ExecGeneratedSourceService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.build.generatedsource.OpenApiGeneratedSourceService;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.generated.GeneratedSourceException;
import sh.zolt.generated.ProtobufGeneratedSourceService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestCompileService {
    private final TestCompileServiceDependencies dependencies;
    private final BuildService buildService;
    private final SourceDiscoverer sourceDiscoverer;
    private final ResourceCopier resourceCopier;
    private final BuildFingerprintService buildFingerprintService;
    private final JdkChecker jdkDetector;
    private final OpenApiGeneratedSourceService openApiGeneratedSourceService;
    private final ProtobufGeneratedSourceService protobufGeneratedSourceService;
    private final ExecGeneratedSourceService execGeneratedSourceService;
    private final GeneratedSourceProducerFingerprintService
            generatedProducerFingerprintService;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final TestCompileSourceExecutor sourceExecutor;
    private final BuildCacheService buildCacheService;
    private final TestCompileCacheGate cacheGate;

    public TestCompileService() {
        this(new JdkDetector());
    }

    public TestCompileService(JdkChecker jdkDetector) {
        this(jdkDetector, new ResolveService());
    }

    public TestCompileService(JdkChecker jdkDetector, ResolveService resolveService) {
        this(TestCompileServiceDependencies.create(jdkDetector, resolveService));
    }

    TestCompileService(
            BuildService buildService,
            SourceDiscoverer sourceDiscoverer,
            ResourceCopier resourceCopier,
            BuildFingerprintService buildFingerprintService,
            JdkChecker jdkDetector,
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner,
            OpenApiGeneratedSourceService openApiGeneratedSourceService) {
        this(TestCompileServiceDependencies.create(
                buildService,
                sourceDiscoverer,
                resourceCopier,
                buildFingerprintService,
                jdkDetector,
                javacRunner,
                groovyCompilerRunner,
                openApiGeneratedSourceService));
    }

    TestCompileService(TestCompileServiceDependencies dependencies) {
        this.dependencies = dependencies;
        this.buildService = dependencies.buildService();
        this.sourceDiscoverer = dependencies.sourceDiscoverer();
        this.resourceCopier = dependencies.resourceCopier();
        this.buildFingerprintService = dependencies.buildFingerprintService();
        this.jdkDetector = dependencies.jdkDetector();
        this.openApiGeneratedSourceService = dependencies.openApiGeneratedSourceService();
        this.protobufGeneratedSourceService = dependencies.protobufGeneratedSourceService();
        this.execGeneratedSourceService = dependencies.execGeneratedSourceService();
        this.generatedProducerFingerprintService =
                dependencies.producerFingerprintService();
        this.incrementalCompileStateRecorder = dependencies.incrementalCompileStateRecorder();
        this.sourceExecutor = dependencies.sourceExecutor();
        this.buildCacheService = dependencies.buildCacheService();
        this.cacheGate = new TestCompileCacheGate(
                buildCacheService,
                buildFingerprintService);
    }

    /**
     * Returns a service that uses the given build-output cache for both the main build it triggers and
     * the test-class compile. The default is a disabled no-op, so existing callers are unaffected.
     */
    public TestCompileService withBuildCache(BuildCacheService cache) {
        return new TestCompileService(dependencies.withBuildCache(cache));
    }

    TestCompileService(
            BuildService buildService,
            SourceDiscoverer sourceDiscoverer,
            ResourceCopier resourceCopier,
            BuildFingerprintService buildFingerprintService,
            JdkChecker jdkDetector,
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner) {
        this(
                buildService,
                sourceDiscoverer,
                resourceCopier,
                buildFingerprintService,
                jdkDetector,
                javacRunner,
                groovyCompilerRunner,
                new OpenApiGeneratedSourceService(jdkDetector));
    }

    public TestCompileResult compileTests(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return compileTestsWithClasspaths(projectDirectory, config, cacheRoot).testCompileResult();
    }

    public TestCompileResultWithClasspaths compileTestsWithClasspaths(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot) {
        BuildResultWithClasspaths buildResult = buildTestInputs(projectDirectory, config, cacheRoot);
        return new TestCompileResultWithClasspaths(
                compileTests(
                        projectDirectory,
                        config,
                        buildResult.classpaths(),
                        buildResult.buildResult(),
                        buildResult.classpathPackages()),
                buildResult.classpaths());
    }

    public BuildResultWithClasspaths buildTestInputs(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return buildTestInputs(projectDirectory, config, cacheRoot, new VerifiedArtifactIndex());
    }

    public BuildResultWithClasspaths buildTestInputs(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        return buildService.buildWithClasspaths(
                projectDirectory,
                config,
                cacheRoot,
                false,
                artifactIndex);
    }

    public TestCompileResult compileTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            BuildResult buildResult) {
        return compileTests(projectDirectory, config, classpaths, buildResult, List.of());
    }

    public TestCompileResult compileTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            BuildResult buildResult,
            List<ResolvedClasspathPackage> classpathPackages) {
        openApiGeneratedSourceService.generateTest(projectDirectory, config, classpathPackages);
        try {
            protobufGeneratedSourceService.generateTest(projectDirectory, config);
        } catch (GeneratedSourceException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
        execGeneratedSourceService.generateTest(projectDirectory, config, classpathPackages);
        SourceDiscoveryResult sources = sourceDiscoverer.discover(projectDirectory, config.build());
        List<GeneratedSourceProducerFingerprint>
                generatedProducerFingerprints =
                        generatedProducerFingerprintService
                                .fingerprintsTest(
                                        projectDirectory,
                                        config,
                                        classpathPackages);
        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw new BuildException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
        }

        List<Path> testCompileEntries = new ArrayList<>();
        testCompileEntries.add(buildResult.outputDirectory());
        testCompileEntries.addAll(classpaths.testCompile().entries());
        Path outputDirectory = projectDirectory.resolve(config.build().testOutput());
        Classpath testCompileClasspath = new Classpath(testCompileEntries);
        List<Path> groovyCompileEntries = new ArrayList<>();
        groovyCompileEntries.add(outputDirectory);
        groovyCompileEntries.addAll(testCompileEntries);
        Classpath groovyCompileClasspath = new Classpath(groovyCompileEntries);
        Path generatedSourcesDirectory = generatedSourcesDirectory(projectDirectory, config.compilerSettings().generatedTestSources());
        Path lockfilePath = ProjectLockfile.in(projectDirectory);
        long fingerprintCheckStarted = System.nanoTime();
        BuildFingerprintCheck fingerprintCheck = buildFingerprintService.checkTestCompileCurrent(
                projectDirectory,
                config,
                lockfilePath,
                sources,
                generatedProducerFingerprints,
                testCompileClasspath,
                classpaths.testProcessor(),
                outputDirectory,
                generatedSourcesDirectory);
        boolean compileSkipped = fingerprintCheck.current();
        long fingerprintCheckNanos = elapsedSince(fingerprintCheckStarted);

        // On a fingerprint miss, restore test classes from the build cache instead of recompiling. Same
        // discipline as the main scope: cold builds only, hermetic modules only, no incremental state
        // left behind (the next edit does one full recompile that re-stores).
        BuildCacheKey cacheKey = cacheGate.key(
                compileSkipped, projectDirectory, config, lockfilePath, sources,
                generatedProducerFingerprints, testCompileClasspath, classpaths.testProcessor(),
                outputDirectory, generatedSourcesDirectory, jdkStatus);
        boolean restored = false;
        if (cacheKey != null) {
            BuildCacheRestoreResult restore = buildCacheService.restore(cacheKey, outputDirectory);
            restored = restore.restored();
        }
        boolean runCompile = !compileSkipped && !restored;

        TestCompileSourceExecutor.Attempt compileAttempt = sourceExecutor.compile(
                !runCompile,
                projectDirectory,
                config,
                sources,
                classpaths,
                testCompileClasspath,
                groovyCompileClasspath,
                outputDirectory,
                generatedSourcesDirectory,
                jdkStatus);
        // Post-compile exec steps run after test compilation, before test resource copy consumes them.
        execGeneratedSourceService.generateTestPostCompile(projectDirectory, config, classpathPackages, false);
        ResourceCopyResult resourceResult = resourceCopier.copyTestResources(projectDirectory, config);
        long fingerprintWriteNanos = 0L;
        if (!compileSkipped || !fingerprintCheck.reason().isBlank()) {
            long fingerprintWriteStarted = System.nanoTime();
            buildFingerprintService.writeTestCompileFingerprint(
                    projectDirectory,
                    config,
                    lockfilePath,
                    sources,
                    generatedProducerFingerprintService
                            .fingerprintsTest(
                                    projectDirectory,
                                    config,
                                    classpathPackages),
                    testCompileClasspath,
                    classpaths.testProcessor(),
                    outputDirectory,
                    generatedSourcesDirectory);
            fingerprintWriteNanos = elapsedSince(fingerprintWriteStarted);
            if (!compileSkipped) {
                if (restored) {
                    incrementalCompileStateRecorder.deleteTestState(outputDirectory);
                } else {
                    incrementalCompileStateRecorder.recordTest(
                            projectDirectory,
                            config,
                            sources,
                            testCompileClasspath,
                            classpaths.testProcessor(),
                            outputDirectory,
                            generatedSourcesDirectory,
                            compileAttempt.attribution(),
                            compileAttempt.compiledSources());
                    if (cacheKey != null) {
                        buildCacheService.store(cacheKey, outputDirectory);
                    }
                }
            }
        }
        return new TestCompileResult(
                buildResult,
                compileAttempt.sourceCount(),
                resourceResult.resourceCount(),
                compileAttempt.outputDirectory(),
                compileAttempt.output(),
                compileSkipped,
                compileSkipped ? "skipped" : (restored ? "restored" : compileAttempt.mode()),
                runCompile ? compileAttempt.fallbackReason() : "",
                compileAttempt.diagnostics(),
                fingerprintCheckNanos,
                fingerprintWriteNanos);
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private static Path generatedSourcesDirectory(Path projectDirectory, String configuredPath) {
        Path configured = Path.of(configuredPath);
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path path = projectRoot.resolve(configured).normalize();
        if (configured.isAbsolute() || !path.startsWith(projectRoot) || path.equals(projectRoot)) {
            throw new BuildException(
                    "Invalid generated test source output path `"
                            + configuredPath
                            + "`. Use a project-relative path under the project directory.");
        }
        return path;
    }

}
