package sh.zolt.build;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
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
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.generated.GeneratedSourceException;
import sh.zolt.generated.ProtobufGeneratedSourceService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BuildService {
    private final BuildServiceDependencies dependencies;
    private final BuildClasspathResolver buildClasspathResolver;
    private final ClasspathBuilder classpathBuilder;
    private final SourceDiscoverer sourceDiscoverer;
    private final BuildFingerprintService buildFingerprintService;
    private final JdkChecker jdkDetector;
    private final OpenApiGeneratedSourceService openApiGeneratedSourceService;
    private final ProtobufGeneratedSourceService protobufGeneratedSourceService;
    private final ExecGeneratedSourceService execGeneratedSourceService;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final MainCompileSourceExecutor sourceExecutor;
    private final MainBuildCacheGate mainBuildCacheGate;
    private final BuildOutputFinalizer outputFinalizer;

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
        this.buildClasspathResolver =
                new BuildClasspathResolver(dependencies.resolveService(), dependencies.lockfileReader());
        this.classpathBuilder = dependencies.classpathBuilder();
        this.sourceDiscoverer = dependencies.sourceDiscoverer();
        this.buildFingerprintService = dependencies.buildFingerprintService();
        this.jdkDetector = dependencies.jdkDetector();
        this.openApiGeneratedSourceService = dependencies.openApiGeneratedSourceService();
        this.protobufGeneratedSourceService = dependencies.protobufGeneratedSourceService();
        this.execGeneratedSourceService = dependencies.execGeneratedSourceService();
        this.incrementalCompileStateRecorder = dependencies.incrementalCompileStateRecorder();
        this.sourceExecutor = dependencies.sourceExecutor();
        this.mainBuildCacheGate =
                new MainBuildCacheGate(dependencies.buildCacheService(), dependencies.buildFingerprintService());
        this.outputFinalizer = new BuildOutputFinalizer(dependencies);
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
        return build(projectDirectory, config, cacheRoot, false, new VerifiedArtifactIndex());
    }

    public BuildResult build(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean offline) {
        return build(projectDirectory, config, cacheRoot, offline, new VerifiedArtifactIndex());
    }

    public BuildResult build(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline,
            VerifiedArtifactIndex artifactIndex) {
        return build(new BuildRequest(projectDirectory, config, cacheRoot, offline, artifactIndex));
    }

    private BuildResult build(BuildRequest request) {
        return buildWithClasspaths(request).buildResult();
    }

    public BuildResultWithClasspaths buildWithClasspaths(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline) {
        return buildWithClasspaths(
                projectDirectory,
                config,
                cacheRoot,
                offline,
                new VerifiedArtifactIndex());
    }

    public BuildResultWithClasspaths buildWithClasspaths(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline,
            VerifiedArtifactIndex artifactIndex) {
        return buildWithClasspaths(
                new BuildRequest(projectDirectory, config, cacheRoot, offline, artifactIndex));
    }

    /** Builds against a command-specific projection of the fully verified lockfile packages. */
    public BuildResultWithClasspaths buildWithClasspaths(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline,
            VerifiedArtifactIndex artifactIndex,
            java.util.function.Predicate<ResolvedClasspathPackage> packageFilter) {
        return buildWithClasspaths(
                new BuildRequest(projectDirectory, config, cacheRoot, offline, artifactIndex, packageFilter));
    }

    private BuildResultWithClasspaths buildWithClasspaths(BuildRequest request) {
        BuildClasspathResolver.Result resolved = buildClasspathResolver.resolve(request);
        List<ResolvedClasspathPackage> classpathPackages = resolved.packages();
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
                build(request.projectDirectory(), request.config(), classpaths, resolved.resolveResult(), classpathPackages,
                        request.offline()),
                classpaths,
                classpathPackages);
    }

    public BuildResult build(Path projectDirectory, ProjectConfig config, ClasspathSet classpaths) {
        return build(projectDirectory, config, classpaths, Optional.empty(), List.of(), false);
    }

    public int ensureCleanMemberOutputsCurrent(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths) {
        return outputFinalizer
                .ensureCleanMemberCurrent(projectDirectory, config, classpaths)
                .generatedOutputCount();
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
        BuildOutputFinalizer.Result finalization = outputFinalizer.afterCompile(
                projectDirectory,
                config,
                outputDirectory,
                jdkStatus,
                classpaths,
                classpathPackages,
                offline);
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
                finalization.generatedOutputCount(),
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
