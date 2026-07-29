package sh.zolt.build;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.compile.MainCompileSourceExecutor;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintCheck;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.ExecGeneratedSourceService;
import sh.zolt.build.generatedsource.OpenApiGeneratedSourceService;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.build.metadata.BuildMetadataGenerator;
import sh.zolt.build.metadata.BuildMetadataResult;
import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.build.resources.ResourceCopyResult;
import sh.zolt.build.springboot.SpringBootAotGenerationService;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.generated.GeneratedSourceException;
import sh.zolt.generated.ProtobufGeneratedSourceService;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BuildService {
    private final BuildServiceDependencies dependencies;
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
    private final MainCompileSourceExecutor sourceExecutor;
    private final MainBuildCacheGate mainBuildCacheGate;

    public BuildService() {
        this(new JdkDetector());
    }

    public BuildService(ResolveService resolveService) {
        this(new JdkDetector(), resolveService);
    }

    public BuildService(ResolveService resolveService, BuildProvenanceSource provenanceSource) {
        this(new JdkDetector(), resolveService, provenanceSource);
    }

    public BuildService(JdkChecker jdkDetector) {
        this(jdkDetector, new ResolveService());
    }

    public BuildService(JdkChecker jdkDetector, ResolveService resolveService) {
        this(BuildServiceDependencies.create(jdkDetector, resolveService));
    }

    public BuildService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            BuildProvenanceSource provenanceSource) {
        this(BuildServiceDependencies.create(jdkDetector, resolveService, provenanceSource));
    }

    BuildService(BuildServiceDependencies dependencies) {
        this.dependencies = dependencies;
        this.resolveService = dependencies.resolveService();
        this.lockfileReader = dependencies.lockfileReader();
        this.classpathBuilder = dependencies.classpathBuilder();
        this.sourceDiscoverer = dependencies.sourceDiscoverer();
        this.resourceCopier = dependencies.resourceCopier();
        this.buildMetadataGenerator = dependencies.buildMetadataGenerator();
        this.buildFingerprintService = dependencies.buildFingerprintService();
        this.jdkDetector = dependencies.jdkDetector();
        this.openApiGeneratedSourceService = dependencies.openApiGeneratedSourceService();
        this.protobufGeneratedSourceService = dependencies.protobufGeneratedSourceService();
        this.execGeneratedSourceService = dependencies.execGeneratedSourceService();
        this.springBootAotGenerationService = dependencies.springBootAotGenerationService();
        this.incrementalCompileStateRecorder = dependencies.incrementalCompileStateRecorder();
        this.sourceExecutor = dependencies.sourceExecutor();
        this.mainBuildCacheGate =
                new MainBuildCacheGate(dependencies.buildCacheService(), dependencies.buildFingerprintService());
    }

    public BuildService withJdkChecker(JdkChecker jdkChecker) {
        Objects.requireNonNull(jdkChecker, "jdkChecker");
        return new BuildService(dependencies.withJdkChecker(jdkChecker));
    }

    /**
     * Returns a build service that uses the given build-output cache. The CLI injects an enabled cache
     * built from the user-global {@code [buildCache]} config; the default is a disabled no-op so every
     * other construction path (and all existing tests) behaves exactly as before.
     */
    public BuildService withBuildCache(BuildCacheService buildCacheService) {
        Objects.requireNonNull(buildCacheService, "buildCacheService");
        return new BuildService(dependencies.withBuildCache(buildCacheService));
    }

    public BuildResult build(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return build(new BuildRequest(projectDirectory, config, cacheRoot, false));
    }

    public BuildResult build(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean offline) {
        return build(new BuildRequest(projectDirectory, config, cacheRoot, offline));
    }

    private BuildResult build(BuildRequest request) {
        return buildWithClasspaths(request).buildResult();
    }

    public BuildResultWithClasspaths buildWithClasspaths(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline) {
        return buildWithClasspaths(new BuildRequest(projectDirectory, config, cacheRoot, offline));
    }

    private BuildResultWithClasspaths buildWithClasspaths(BuildRequest request) {
        Path lockfilePath = request.projectDirectory().resolve("zolt.lock");
        Optional<ResolveResult> resolveResult = Optional.empty();
        if (!Files.isRegularFile(lockfilePath)) {
            resolveResult = Optional.of(resolveService.resolve(
                    request.projectDirectory(),
                    request.config(),
                    request.cacheRoot(),
                    false,
                    ResolveOptions.offline(request.offline()).withRetryCommand("zolt build")));
        }
        if (GeneratedSourceToolingGate.openApiToolingMissing(
                        lockfileReader, request.projectDirectory(), request.config(), request.offline())
                || GeneratedSourceToolingGate.execToolingMissing(
                        lockfileReader, request.projectDirectory(), request.config(), request.offline())) {
            resolveResult = Optional.of(resolveService.resolve(
                    request.projectDirectory(),
                    request.config(),
                    request.cacheRoot(),
                    false,
                    ResolveOptions.offline(request.offline()).withRetryCommand("zolt build")));
        }

        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        List<ResolvedClasspathPackage> classpathPackages = LockfileClasspathPackageConverter.classpathPackages(
                lockfile,
                request.cacheRoot());
        ClasspathSet classpaths = classpathBuilder.build(classpathPackages);
        openApiGeneratedSourceService.generateMain(request.projectDirectory(), request.config(), classpathPackages);
        try {
            protobufGeneratedSourceService.generateMain(request.projectDirectory(), request.config());
        } catch (GeneratedSourceException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
        execGeneratedSourceService.generateMain(
                request.projectDirectory(), request.config(), classpathPackages, request.offline());
        return new BuildResultWithClasspaths(
                build(request.projectDirectory(), request.config(), classpaths, resolveResult, classpathPackages,
                        request.offline()),
                classpaths,
                classpathPackages);
    }

    public BuildResult build(Path projectDirectory, ProjectConfig config, ClasspathSet classpaths) {
        return build(projectDirectory, config, classpaths, Optional.empty(), List.of(), false);
    }

    private BuildResult build(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            Optional<ResolveResult> resolveResult,
            List<ResolvedClasspathPackage> classpathPackages,
            boolean offline) {
        if (config.packageSettings().mode() == sh.zolt.project.PackageMode.BOM) {
            // A BOM has no compiled sources; keep it in the build graph for ordering, but skip the
            // compile wave and produce no class output.
            return new BuildResult(
                    resolveResult, 0, 0, projectDirectory.resolve(config.build().output()), "", true);
        }
        SourceDiscoveryResult sources = sourceDiscoverer.discover(projectDirectory, config.build());
        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw BuildException.actionable("JDK check failed.", String.join(" ", jdkStatus.problems()));
        }

        Path outputDirectory = projectDirectory.resolve(config.build().output());
        Path generatedSourcesDirectory = generatedSourcesDirectory(projectDirectory, config.compilerSettings().generatedSources());
        Path lockfilePath = projectDirectory.resolve("zolt.lock");
        long fingerprintCheckStarted = System.nanoTime();
        BuildFingerprintCheck fingerprintCheck = buildFingerprintService.checkMainCompileCurrent(
                projectDirectory,
                config,
                lockfilePath,
                sources,
                classpaths,
                outputDirectory,
                generatedSourcesDirectory);
        boolean compileSkipped = fingerprintCheck.current();
        long fingerprintCheckNanos = elapsedSince(fingerprintCheckStarted);

        // On a fingerprint miss, try to restore the compiled classes from the build-output cache instead
        // of running javac. A restore is a real (non-skipped) outcome: it still stamps the skip-gate
        // fingerprint below, but leaves no incremental state so the next source edit does one full
        // recompile (the documented v1 tradeoff).
        MainBuildCacheGate.Attempt cacheAttempt = mainBuildCacheGate.attemptRestore(
                compileSkipped,
                projectDirectory,
                config,
                lockfilePath,
                sources,
                classpaths,
                outputDirectory,
                generatedSourcesDirectory,
                jdkStatus);
        boolean restored = cacheAttempt.restored();
        boolean runJavac = !compileSkipped && !restored;

        MainCompileSourceExecutor.Attempt javacResult = sourceExecutor.compile(
                !runJavac,
                fingerprintCheck.reason(),
                projectDirectory,
                config,
                sources,
                classpaths,
                outputDirectory,
                generatedSourcesDirectory,
                jdkStatus);
        // Post-compile exec steps (tool = "project" / inputs under compiled classes) run here, after
        // compilation produces the classes they read, and before resource copy/packaging consumes their
        // outputs. Their outputs are fenced by package/test evidence, not the compile fingerprint.
        execGeneratedSourceService.generateMainPostCompile(projectDirectory, config, classpathPackages, offline);
        ResourceCopyResult resourceResult = resourceCopier.copyMainResources(projectDirectory, config);
        BuildMetadataResult metadataResult = buildMetadataGenerator.generate(projectDirectory, config, outputDirectory);
        springBootAotGenerationService.generate(
                projectDirectory,
                config,
                jdkStatus,
                classpaths,
                springBootAotClasspath(config, classpathPackages));
        long fingerprintWriteNanos = 0L;
        String buildCacheOutcome = "";
        if (!compileSkipped || !fingerprintCheck.reason().isBlank()) {
            long fingerprintWriteStarted = System.nanoTime();
            buildFingerprintService.writeMainCompileFingerprint(
                    projectDirectory,
                    config,
                    lockfilePath,
                    sources,
                    classpaths,
                    outputDirectory,
                    generatedSourcesDirectory);
            fingerprintWriteNanos = elapsedSince(fingerprintWriteStarted);
            if (!compileSkipped) {
                if (restored) {
                    incrementalCompileStateRecorder.deleteMainState(outputDirectory);
                    buildCacheOutcome = "restored";
                } else {
                    incrementalCompileStateRecorder.recordMain(
                            projectDirectory,
                            config,
                            sources,
                            classpaths,
                            outputDirectory,
                            generatedSourcesDirectory,
                            javacResult.attribution(),
                            javacResult.compiledSources());
                    buildCacheOutcome = mainBuildCacheGate.store(cacheAttempt, outputDirectory);
                }
            }
        }
        return new BuildResult(
                resolveResult,
                javacResult.sourceCount(),
                resourceResult.resourceCount() + metadataResult.generatedCount(),
                javacResult.outputDirectory(),
                javacResult.output(),
                compileSkipped,
                compileSkipped ? "skipped" : (restored ? "restored" : javacResult.mode()),
                runJavac ? javacResult.fallbackReason() : "",
                javacResult.diagnostics(),
                fingerprintCheckNanos,
                fingerprintWriteNanos,
                restored ? cacheAttempt.restore().classCount() : 0,
                buildCacheOutcome);
    }

    private static Classpath springBootAotClasspath(
            ProjectConfig config,
            List<ResolvedClasspathPackage> classpathPackages) {
        if (!config.frameworkSettings().springBoot().nativeEnabled()) {
            return new Classpath(List.of());
        }
        return new Classpath(classpathPackages.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_SPRING_AOT)
                .map(dependency -> dependency.resolvedPackage().jarPath())
                .toList());
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
                    "Invalid generated source output path `"
                            + configuredPath
                            + "`. Use a project-relative path under the project directory.");
        }
        return path;
    }

}

record BuildRequest(
        Path projectDirectory,
        ProjectConfig config,
        Path cacheRoot,
        boolean offline) {
    BuildRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
    }
}
