package sh.zolt.ide;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.error.ActionableException;
import sh.zolt.generated.GeneratedSourceEvidenceService;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.ContentAddressedLockCapability;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class IdeModelService {
    private static final int SCHEMA_VERSION = 1;

    private final ManifestProjectConfigLoader manifestLoader;
    private final ZoltLockfileReader lockfileReader;
    private final ResolveService resolveService;
    private final IdeRootModelBuilder rootModelBuilder;
    private final IdeProjectModelBuilder projectModelBuilder;
    private final IdeClasspathModelBuilder classpathModelBuilder;
    private final IdeDependencyModelBuilder dependencyModelBuilder;
    private final IdeFrameworkModelProvider frameworkModelProvider;

    public IdeModelService() {
        this(IdeFrameworkModelProvider.none());
    }

    public IdeModelService(IdeFrameworkModelProvider frameworkModelProvider) {
        this(
                new ManifestProjectConfigLoader(),
                new ZoltLockfileReader(),
                new ResolveService(),
                new GeneratedSourceEvidenceService(),
                new IdeProjectModelBuilder(),
                new IdeClasspathModelBuilder(),
                new IdeDependencyModelBuilder(),
                frameworkModelProvider);
    }

    IdeModelService(
            ManifestProjectConfigLoader manifestLoader,
            ZoltLockfileReader lockfileReader,
            ResolveService resolveService,
            GeneratedSourceEvidenceService generatedSourceEvidenceService,
            IdeProjectModelBuilder projectModelBuilder,
            IdeClasspathModelBuilder classpathModelBuilder,
            IdeDependencyModelBuilder dependencyModelBuilder,
            IdeFrameworkModelProvider frameworkModelProvider) {
        this.manifestLoader = manifestLoader;
        this.lockfileReader = lockfileReader;
        this.resolveService = resolveService;
        this.rootModelBuilder = new IdeRootModelBuilder(generatedSourceEvidenceService);
        this.projectModelBuilder = projectModelBuilder;
        this.classpathModelBuilder = classpathModelBuilder;
        this.dependencyModelBuilder = dependencyModelBuilder;
        this.frameworkModelProvider = frameworkModelProvider;
    }

    public IdeModel export(Path projectDirectory, Path cacheRoot) {
        return export(projectDirectory, cacheRoot, false, false);
    }

    public IdeModel export(Path projectDirectory, Path cacheRoot, boolean checkLock, boolean offline) {
        return export(projectDirectory, cacheRoot, checkLock, offline, IdeTimingRecorder.disabled());
    }

    public IdeModel export(
            Path projectDirectory,
            Path cacheRoot,
            boolean checkLock,
            boolean offline,
            IdeTimingRecorder timings) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        Path configPath = root.resolve("zolt.toml").normalize();
        Path lockfilePath = root.resolve("zolt.lock").normalize();
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath().normalize();
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeTimingRecorder recorder = timings == null ? IdeTimingRecorder.disabled() : timings;
        ProjectConfig config = recorder.measure(
                "read ide project config",
                () -> readConfig(configPath, diagnostics));
        IdeModel.ClasspathInfo classpaths = recorder.measure(
                "build ide classpaths",
                () -> classpathModelBuilder.build(lockfilePath, normalizedCacheRoot, root, config, diagnostics),
                IdeModelService::ideClasspathAttributes);
        if (checkLock) {
            recorder.measure(
                    "check ide lock freshness",
                    () -> checkLockFreshness(root, lockfilePath, normalizedCacheRoot, config, offline, diagnostics));
        }
        IdeModel.FrameworkInfo frameworkInfo = recorder.measure(
                "build ide framework model",
                () -> frameworkModelProvider.build(root, normalizedCacheRoot, config, diagnostics));

        return recorder.measure(
                "assemble ide model",
                () -> new IdeModel(
                        SCHEMA_VERSION,
                        projectModelBuilder.projectInfo(config),
                        projectModelBuilder.javaInfo(config),
                        projectModelBuilder.compilerInfo(root, config, diagnostics),
                        projectModelBuilder.testRuntimeInfo(config),
                        projectModelBuilder.packageInfo(root, config, diagnostics),
                        new IdeModel.PathInfo(root, configPath, lockfilePath),
                        rootModelBuilder.sourceRoots(root, config, diagnostics),
                        rootModelBuilder.generatedSources(root, config, diagnostics),
                        rootModelBuilder.resourceRoots(root, config, diagnostics),
                        projectModelBuilder.outputInfo(root, config, diagnostics),
                        dependencyModelBuilder.build(config),
                        classpaths,
                        frameworkInfo,
                        diagnostics),
                IdeModelService::ideModelAttributes);
    }

    private static Map<String, String> ideClasspathAttributes(IdeModel.ClasspathInfo classpaths) {
        return Map.of(
                "compileClasspathEntries", Integer.toString(classpaths.compile().size()),
                "runtimeClasspathEntries", Integer.toString(classpaths.runtime().size()),
                "testClasspathEntries", Integer.toString(classpaths.test().size()));
    }

    private static Map<String, String> ideModelAttributes(IdeModel model) {
        return Map.of(
                "sourceRoots", Integer.toString(model.sourceRoots().size()),
                "resourceRoots", Integer.toString(model.resourceRoots().size()),
                "diagnostics", Integer.toString(model.diagnostics().size()));
    }

    IdeModel exportWithClasspaths(
            Path projectDirectory,
            Path lockfilePath,
            IdeModel.ClasspathInfo classpaths,
            List<IdeModel.Diagnostic> diagnostics) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        Path configPath = root.resolve("zolt.toml").normalize();
        List<IdeModel.Diagnostic> modelDiagnostics = new ArrayList<>(diagnostics);
        ProjectConfig config = readConfig(configPath, modelDiagnostics);

        return new IdeModel(
                SCHEMA_VERSION,
                projectModelBuilder.projectInfo(config),
                projectModelBuilder.javaInfo(config),
                projectModelBuilder.compilerInfo(root, config, modelDiagnostics),
                projectModelBuilder.testRuntimeInfo(config),
                projectModelBuilder.packageInfo(root, config, modelDiagnostics),
                new IdeModel.PathInfo(root, configPath, lockfilePath.toAbsolutePath().normalize()),
                rootModelBuilder.sourceRoots(root, config, modelDiagnostics),
                rootModelBuilder.generatedSources(root, config, modelDiagnostics),
                rootModelBuilder.resourceRoots(root, config, modelDiagnostics),
                projectModelBuilder.outputInfo(root, config, modelDiagnostics),
                dependencyModelBuilder.build(config),
                classpaths,
                frameworkModelProvider.build(root, null, config, modelDiagnostics),
                modelDiagnostics);
    }

    private void checkLockFreshness(
            Path root,
            Path lockfilePath,
            Path cacheRoot,
            ProjectConfig config,
            boolean offline,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null || !Files.isRegularFile(lockfilePath)) {
            return;
        }
        try {
            ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
            if (!ContentAddressedLockCapability.supportsArtifactCachePaths(lockfile)) {
                diagnostics.add(new IdeModel.Diagnostic(
                        "error",
                        "LOCKFILE_MIGRATION_REQUIRED",
                        "zolt.lock version "
                                + lockfile.version()
                                + " predates the version "
                                + ContentAddressedLockCapability.MINIMUM_VERSION
                                + " content-addressed artifact cache path contract required by this Zolt.",
                        lockfilePath,
                        "Run zolt resolve."));
                return;
            }
        } catch (LockfileReadException exception) {
            return;
        }
        try {
            resolveService.resolve(root, config, cacheRoot, true, offline);
        } catch (ResolveException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    lockDiagnosticCode(exception),
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve."));
        } catch (ArtifactCacheException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    "LOCKFILE_CHECK_UNAVAILABLE",
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve without --offline to seed the cache, then retry zolt ide model --offline."));
        } catch (ActionableException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    "LOCKFILE_MIGRATION_REQUIRED",
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve."));
        }
    }

    private static String lockDiagnosticCode(ResolveException exception) {
        return exception.getMessage().contains("out of date")
                ? "LOCKFILE_STALE"
                : "LOCKFILE_CHECK_FAILED";
    }

    private ProjectConfig readConfig(Path configPath, List<IdeModel.Diagnostic> diagnostics) {
        try {
            return manifestLoader.load(configPath);
        } catch (ZoltConfigException exception) {
            String code = exception.getMessage().startsWith("Could not read zolt.toml")
                    ? "CONFIG_UNREADABLE"
                    : "CONFIG_INVALID";
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    code,
                    exception.getMessage(),
                    configPath,
                    "Fix zolt.toml and run zolt ide model --format json again."));
            return null;
        }
    }

}
