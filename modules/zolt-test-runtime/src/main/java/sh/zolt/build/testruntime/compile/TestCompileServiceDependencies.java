package sh.zolt.build.testruntime.compile;

import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.BuildService;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.compile.GroovyCompilerRunner;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.compile.KotlinCompilerRunner;
import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.generatedsource.ExecGeneratedSourceService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.build.generatedsource.OpenApiGeneratedSourceService;
import sh.zolt.build.incremental.IncrementalCompilePlanner;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.generated.ProtobufGeneratedSourceService;
import sh.zolt.resolve.ResolveService;

final class TestCompileServiceDependencies {
    private final BuildService buildService;
    private final SourceDiscoverer sourceDiscoverer;
    private final ResourceCopier resourceCopier;
    private final BuildFingerprintService buildFingerprintService;
    private final JdkChecker jdkDetector;
    private final TestGeneratedSourceCoordinator generatedSourceCoordinator;
    private final GeneratedSourceProducerFingerprintService
            producerFingerprintService;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final TestCompileSourceExecutor sourceExecutor;
    private final BuildCacheService buildCacheService;

    private TestCompileServiceDependencies(
            TestInputDependencies testInputDependencies,
            GeneratedSourceDependencies generatedSourceDependencies,
            TestSourceExecutorDependencies executorDependencies,
            BuildCacheService buildCacheService) {
        this.buildService = testInputDependencies.buildService();
        this.sourceDiscoverer = testInputDependencies.sourceDiscoverer();
        this.resourceCopier = testInputDependencies.resourceCopier();
        this.buildFingerprintService = testInputDependencies.buildFingerprintService();
        this.jdkDetector = generatedSourceDependencies.jdkDetector();
        this.generatedSourceCoordinator = generatedSourceDependencies.generatedSourceCoordinator();
        this.producerFingerprintService =
                generatedSourceDependencies.producerFingerprintService();
        this.incrementalCompileStateRecorder = executorDependencies.incrementalCompileStateRecorder();
        this.sourceExecutor = executorDependencies.sourceExecutor();
        this.buildCacheService = buildCacheService;
    }

    static TestCompileServiceDependencies create(JdkChecker jdkDetector, ResolveService resolveService) {
        return create(jdkDetector, resolveService, new IncrementalCompilePlanner());
    }

    static TestCompileServiceDependencies create(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            IncrementalCompilePlanner incrementalCompilePlanner) {
        return create(
                new BuildService(jdkDetector, resolveService),
                new SourceDiscoverer(),
                new ResourceCopier(),
                new BuildFingerprintService(),
                jdkDetector,
                new JavacRunner(),
                new GroovyCompilerRunner(),
                new OpenApiGeneratedSourceService(jdkDetector),
                incrementalCompilePlanner);
    }

    static TestCompileServiceDependencies create(
            BuildService buildService,
            SourceDiscoverer sourceDiscoverer,
            ResourceCopier resourceCopier,
            BuildFingerprintService buildFingerprintService,
            JdkChecker jdkDetector,
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner,
            OpenApiGeneratedSourceService openApiGeneratedSourceService) {
        return create(
                buildService,
                sourceDiscoverer,
                resourceCopier,
                buildFingerprintService,
                jdkDetector,
                javacRunner,
                groovyCompilerRunner,
                openApiGeneratedSourceService,
                new IncrementalCompilePlanner());
    }

    private static TestCompileServiceDependencies create(
            BuildService buildService,
            SourceDiscoverer sourceDiscoverer,
            ResourceCopier resourceCopier,
            BuildFingerprintService buildFingerprintService,
            JdkChecker jdkDetector,
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner,
            OpenApiGeneratedSourceService openApiGeneratedSourceService,
            IncrementalCompilePlanner incrementalCompilePlanner) {
        IncrementalCompileStateRecorder incrementalCompileStateRecorder = new IncrementalCompileStateRecorder();
        ProtobufGeneratedSourceService protobuf = new ProtobufGeneratedSourceService();
        ExecGeneratedSourceService exec = new ExecGeneratedSourceService(jdkDetector);
        return new TestCompileServiceDependencies(
                new TestInputDependencies(
                        buildService,
                        sourceDiscoverer,
                        resourceCopier,
                        buildFingerprintService),
                new GeneratedSourceDependencies(
                        jdkDetector,
                        new TestGeneratedSourceCoordinator(
                                openApiGeneratedSourceService,
                                protobuf,
                                exec,
                                new KspTestGenerationCoordinator(sourceDiscoverer, jdkDetector)),
                        new GeneratedSourceProducerFingerprintService()),
                new TestSourceExecutorDependencies(
                        incrementalCompileStateRecorder,
                        new TestCompileSourceExecutor(
                                javacRunner,
                                groovyCompilerRunner,
                                new KotlinCompilerRunner(),
                                incrementalCompileStateRecorder,
                                incrementalCompilePlanner)),
                BuildCacheService.disabled());
    }

    /**
     * Returns dependencies whose build service and cache use the given build-output cache. Everything else
     * is carried over, so a service rebuilt from these behaves exactly as before apart from cache use.
     */
    TestCompileServiceDependencies withBuildCache(BuildCacheService buildCacheService) {
        return new TestCompileServiceDependencies(
                new TestInputDependencies(
                        buildService.withBuildCache(buildCacheService),
                        sourceDiscoverer,
                        resourceCopier,
                        buildFingerprintService),
                new GeneratedSourceDependencies(
                        jdkDetector,
                        generatedSourceCoordinator,
                        producerFingerprintService),
                new TestSourceExecutorDependencies(
                        incrementalCompileStateRecorder,
                        sourceExecutor),
                buildCacheService);
    }

    BuildService buildService() {
        return buildService;
    }

    SourceDiscoverer sourceDiscoverer() {
        return sourceDiscoverer;
    }

    ResourceCopier resourceCopier() {
        return resourceCopier;
    }

    BuildFingerprintService buildFingerprintService() {
        return buildFingerprintService;
    }

    JdkChecker jdkDetector() {
        return jdkDetector;
    }

    TestGeneratedSourceCoordinator generatedSourceCoordinator() {
        return generatedSourceCoordinator;
    }

    GeneratedSourceProducerFingerprintService
            producerFingerprintService() {
        return producerFingerprintService;
    }

    IncrementalCompileStateRecorder incrementalCompileStateRecorder() {
        return incrementalCompileStateRecorder;
    }

    TestCompileSourceExecutor sourceExecutor() {
        return sourceExecutor;
    }

    BuildCacheService buildCacheService() {
        return buildCacheService;
    }

    private record TestInputDependencies(
            BuildService buildService,
            SourceDiscoverer sourceDiscoverer,
            ResourceCopier resourceCopier,
            BuildFingerprintService buildFingerprintService) {
    }

    private record GeneratedSourceDependencies(
            JdkChecker jdkDetector,
            TestGeneratedSourceCoordinator generatedSourceCoordinator,
            GeneratedSourceProducerFingerprintService
                    producerFingerprintService) {
    }

    private record TestSourceExecutorDependencies(
            IncrementalCompileStateRecorder incrementalCompileStateRecorder,
            TestCompileSourceExecutor sourceExecutor) {
    }
}
