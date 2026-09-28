package sh.zolt.build;

import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.compile.MainCompileSourceExecutor;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.ExecGeneratedSourceService;
import sh.zolt.build.generatedsource.OpenApiGeneratedSourceService;
import sh.zolt.build.incremental.IncrementalCompilePlanner;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.build.metadata.BuildMetadataGenerator;
import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.build.springboot.SpringBootAotGenerationService;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.generated.ProtobufGeneratedSourceService;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveService;

final class BuildServiceDependencies {
    private final ResolveService resolveService;
    private final ZoltLockfileReader lockfileReader;
    private final ClasspathBuilder classpathBuilder;
    private final SourceDiscoverer sourceDiscoverer;
    private final ResourceCopier resourceCopier;
    private final BuildMetadataGenerator buildMetadataGenerator;
    private final BuildFingerprintService buildFingerprintService;
    private final JdkChecker jdkDetector;
    private final OpenApiGeneratedSourceService openApiGeneratedSourceService;
    private final ProtobufGeneratedSourceService protobufGeneratedSourceService;
    private final ExecGeneratedSourceService execGeneratedSourceService;
    private final SpringBootAotGenerationService springBootAotGenerationService;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final IncrementalCompilePlanner incrementalCompilePlanner;
    private final MainCompileSourceExecutor sourceExecutor;
    private final BuildCacheService buildCacheService;

    private BuildServiceDependencies(
            ResolveDependencies resolveDependencies,
            OutputDependencies outputDependencies,
            GeneratedSourceDependencies generatedSourceDependencies,
            BuildCacheService buildCacheService) {
        this.buildCacheService = buildCacheService;
        this.resolveService = resolveDependencies.resolveService();
        this.lockfileReader = resolveDependencies.lockfileReader();
        this.classpathBuilder = resolveDependencies.classpathBuilder();
        this.sourceDiscoverer = outputDependencies.sourceDiscoverer();
        this.resourceCopier = outputDependencies.resourceCopier();
        this.buildMetadataGenerator = outputDependencies.buildMetadataGenerator();
        this.buildFingerprintService = outputDependencies.buildFingerprintService();
        this.jdkDetector = generatedSourceDependencies.jdkDetector();
        this.openApiGeneratedSourceService = generatedSourceDependencies.openApiGeneratedSourceService();
        this.protobufGeneratedSourceService = generatedSourceDependencies.protobufGeneratedSourceService();
        this.execGeneratedSourceService = generatedSourceDependencies.execGeneratedSourceService();
        this.springBootAotGenerationService = generatedSourceDependencies.springBootAotGenerationService();
        this.incrementalCompileStateRecorder = generatedSourceDependencies.incrementalCompileStateRecorder();
        this.incrementalCompilePlanner = generatedSourceDependencies.incrementalCompilePlanner();
        this.sourceExecutor = generatedSourceDependencies.sourceExecutor();
    }

    static BuildServiceDependencies create(JdkChecker jdkDetector, ResolveService resolveService) {
        return create(jdkDetector, resolveService, BuildProvenanceSource.empty());
    }

    static BuildServiceDependencies experimentalSelective() {
        return create(
                new JdkDetector(),
                new ResolveService(),
                BuildProvenanceSource.empty(),
                IncrementalCompilePlanner.experimentalSelective());
    }

    static BuildServiceDependencies create(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            BuildProvenanceSource provenanceSource) {
        return create(jdkDetector, resolveService, provenanceSource, new IncrementalCompilePlanner());
    }

    static BuildServiceDependencies create(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            BuildProvenanceSource provenanceSource,
            IncrementalCompilePlanner incrementalCompilePlanner) {
        JavacRunner javacRunner = new JavacRunner();
        IncrementalCompileStateRecorder incrementalCompileStateRecorder = new IncrementalCompileStateRecorder();
        return new BuildServiceDependencies(
                new ResolveDependencies(
                        resolveService,
                        new ZoltLockfileReader(),
                        new ClasspathBuilder()),
                new OutputDependencies(
                        new SourceDiscoverer(),
                        new ResourceCopier(),
                        new BuildMetadataGenerator(provenanceSource),
                        new BuildFingerprintService()),
                new GeneratedSourceDependencies(
                        jdkDetector,
                        new OpenApiGeneratedSourceService(jdkDetector),
                        new ProtobufGeneratedSourceService(),
                        new ExecGeneratedSourceService(jdkDetector),
                        new SpringBootAotGenerationService(javacRunner),
                        incrementalCompileStateRecorder,
                        incrementalCompilePlanner,
                        new MainCompileSourceExecutor(
                                javacRunner,
                                incrementalCompileStateRecorder,
                                incrementalCompilePlanner)),
                BuildCacheService.disabled());
    }

    BuildServiceDependencies withJdkChecker(JdkChecker jdkDetector) {
        JavacRunner javacRunner = new JavacRunner();
        IncrementalCompileStateRecorder incrementalCompileStateRecorder = new IncrementalCompileStateRecorder();
        return new BuildServiceDependencies(
                new ResolveDependencies(
                        resolveService,
                        lockfileReader,
                        classpathBuilder),
                new OutputDependencies(
                        sourceDiscoverer,
                        resourceCopier,
                        buildMetadataGenerator,
                        buildFingerprintService),
                new GeneratedSourceDependencies(
                        jdkDetector,
                        new OpenApiGeneratedSourceService(jdkDetector),
                        protobufGeneratedSourceService,
                        new ExecGeneratedSourceService(jdkDetector),
                        new SpringBootAotGenerationService(javacRunner),
                        incrementalCompileStateRecorder,
                        incrementalCompilePlanner,
                        new MainCompileSourceExecutor(
                                javacRunner,
                                incrementalCompileStateRecorder,
                                incrementalCompilePlanner)),
                buildCacheService);
    }

    BuildServiceDependencies withBuildCache(BuildCacheService buildCacheService) {
        return new BuildServiceDependencies(
                new ResolveDependencies(resolveService, lockfileReader, classpathBuilder),
                new OutputDependencies(
                        sourceDiscoverer,
                        resourceCopier,
                        buildMetadataGenerator,
                        buildFingerprintService),
                new GeneratedSourceDependencies(
                        jdkDetector,
                        openApiGeneratedSourceService,
                        protobufGeneratedSourceService,
                        execGeneratedSourceService,
                        springBootAotGenerationService,
                        incrementalCompileStateRecorder,
                        incrementalCompilePlanner,
                        sourceExecutor),
                buildCacheService);
    }

    BuildCacheService buildCacheService() {
        return buildCacheService;
    }

    ResolveService resolveService() {
        return resolveService;
    }

    ZoltLockfileReader lockfileReader() {
        return lockfileReader;
    }

    ClasspathBuilder classpathBuilder() {
        return classpathBuilder;
    }

    SourceDiscoverer sourceDiscoverer() {
        return sourceDiscoverer;
    }

    ResourceCopier resourceCopier() {
        return resourceCopier;
    }

    BuildMetadataGenerator buildMetadataGenerator() {
        return buildMetadataGenerator;
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

    SpringBootAotGenerationService springBootAotGenerationService() {
        return springBootAotGenerationService;
    }

    IncrementalCompileStateRecorder incrementalCompileStateRecorder() {
        return incrementalCompileStateRecorder;
    }

    MainCompileSourceExecutor sourceExecutor() {
        return sourceExecutor;
    }

    private record ResolveDependencies(
            ResolveService resolveService,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder) {
    }

    private record OutputDependencies(
            SourceDiscoverer sourceDiscoverer,
            ResourceCopier resourceCopier,
            BuildMetadataGenerator buildMetadataGenerator,
            BuildFingerprintService buildFingerprintService) {
    }

    private record GeneratedSourceDependencies(
            JdkChecker jdkDetector,
            OpenApiGeneratedSourceService openApiGeneratedSourceService,
            ProtobufGeneratedSourceService protobufGeneratedSourceService,
            ExecGeneratedSourceService execGeneratedSourceService,
            SpringBootAotGenerationService springBootAotGenerationService,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder,
            IncrementalCompilePlanner incrementalCompilePlanner,
            MainCompileSourceExecutor sourceExecutor) {
    }
}
