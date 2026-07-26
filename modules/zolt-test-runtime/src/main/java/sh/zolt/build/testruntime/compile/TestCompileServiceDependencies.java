package sh.zolt.build.testruntime.compile;

import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.BuildService;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.compile.GroovyCompilerRunner;
import sh.zolt.build.compile.JavacRunner;
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
    private final OpenApiGeneratedSourceService openApiGeneratedSourceService;
    private final ProtobufGeneratedSourceService protobufGeneratedSourceService;
    private final ExecGeneratedSourceService execGeneratedSourceService;
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
        this.openApiGeneratedSourceService = generatedSourceDependencies.openApiGeneratedSourceService();
        this.protobufGeneratedSourceService = generatedSourceDependencies.protobufGeneratedSourceService();
        this.execGeneratedSourceService = generatedSourceDependencies.execGeneratedSourceService();
        this.producerFingerprintService =
                generatedSourceDependencies.producerFingerprintService();
        this.incrementalCompileStateRecorder = executorDependencies.incrementalCompileStateRecorder();
        this.sourceExecutor = executorDependencies.sourceExecutor();
        this.buildCacheService = buildCacheService;
    }

    static TestCompileServiceDependencies create(JdkChecker jdkDetector, ResolveService resolveService) {
        return create(
                new BuildService(jdkDetector, resolveService),
                new SourceDiscoverer(),
                new ResourceCopier(),
                new BuildFingerprintService(),
                jdkDetector,
                new JavacRunner(),
                new GroovyCompilerRunner(),
                new OpenApiGeneratedSourceService(jdkDetector));
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
        IncrementalCompileStateRecorder incrementalCompileStateRecorder = new IncrementalCompileStateRecorder();
        return new TestCompileServiceDependencies(
                new TestInputDependencies(
                        buildService,
                        sourceDiscoverer,
                        resourceCopier,
                        buildFingerprintService),
                new GeneratedSourceDependencies(
                        jdkDetector,
                        openApiGeneratedSourceService,
                        new ProtobufGeneratedSourceService(),
                        new ExecGeneratedSourceService(jdkDetector),
                        new GeneratedSourceProducerFingerprintService()),
                new TestSourceExecutorDependencies(
                        incrementalCompileStateRecorder,
                        new TestCompileSourceExecutor(
                                javacRunner,
                                groovyCompilerRunner,
                                incrementalCompileStateRecorder,
                                new IncrementalCompilePlanner())),
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
                        openApiGeneratedSourceService,
                        protobufGeneratedSourceService,
                        execGeneratedSourceService,
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

    OpenApiGeneratedSourceService openApiGeneratedSourceService() {
        return openApiGeneratedSourceService;
    }

    ProtobufGeneratedSourceService protobufGeneratedSourceService() {
        return protobufGeneratedSourceService;
    }

    ExecGeneratedSourceService execGeneratedSourceService() {
        return execGeneratedSourceService;
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
            OpenApiGeneratedSourceService openApiGeneratedSourceService,
            ProtobufGeneratedSourceService protobufGeneratedSourceService,
            ExecGeneratedSourceService execGeneratedSourceService,
            GeneratedSourceProducerFingerprintService
                    producerFingerprintService) {
    }

    private record TestSourceExecutorDependencies(
            IncrementalCompileStateRecorder incrementalCompileStateRecorder,
            TestCompileSourceExecutor sourceExecutor) {
    }
}
