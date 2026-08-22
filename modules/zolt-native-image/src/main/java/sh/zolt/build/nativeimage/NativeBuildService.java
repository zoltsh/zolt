package sh.zolt.build.nativeimage;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.build.NativeImageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.springboot.SpringBootAotNativeInputs;
import sh.zolt.build.springboot.SpringBootAotOutputEvidenceService;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.project.ProjectPaths;
import sh.zolt.provenance.BuildProvenanceSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class NativeBuildService {
    private static final List<String> SERIOUS_WARNING_TERMS = List.of("warning", "unsupported", "error");

    private final PackageService packageService;
    private final ZoltLockfileReader lockfileReader;
    private final ClasspathBuilder classpathBuilder;
    private final NativeImageRunner nativeImageRunner;

    public NativeBuildService() {
        this(BuildProvenanceSource.empty());
    }

    public NativeBuildService(BuildProvenanceSource provenanceSource) {
        this(
                new PackageService(provenanceSource),
                new ZoltLockfileReader(),
                new ClasspathBuilder(),
                new NativeImageRunner());
    }

    NativeBuildService(
            PackageService packageService,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder,
            NativeImageRunner nativeImageRunner) {
        this.packageService = packageService;
        this.lockfileReader = lockfileReader;
        this.classpathBuilder = classpathBuilder;
        this.nativeImageRunner = nativeImageRunner;
    }

    public NativeBuildResult buildNative(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            Path nativeImageExecutable) {
        return buildNative(
                projectDirectory,
                config,
                cacheRoot,
                nativeImageExecutable,
                () -> {
                });
    }

    public NativeBuildResult buildNative(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            Path nativeImageExecutable,
            Runnable progress) {
        return buildNative(
                projectDirectory,
                config,
                cacheRoot,
                nativeImageExecutable,
                progress,
                new VerifiedArtifactIndex());
    }

    /** Builds through the artifact index established by command-level lock freshness. */
    public NativeBuildResult buildNative(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            Path nativeImageExecutable,
            Runnable progress,
            VerifiedArtifactIndex artifactIndex) {
        NativeFrameworkPolicy.rejectUnsupported(config);
        nativeMainClass(config);
        preflightNativeImageExecutable(projectDirectory, nativeImageExecutable);
        NativeOutputPlan outputPlan = NativeOutputPlan.plan(
                projectDirectory,
                config,
                cacheRoot,
                nativeImageExecutable);
        ProjectConfig packageConfig = NativePackagePolicy.packageConfig(config);
        var packageFilter = NativePackagePolicy.classpathFilter(config);
        PackageResult packageResult = packageService.packageJar(
                projectDirectory,
                config,
                packageConfig,
                cacheRoot,
                artifactIndex);
        ZoltLockfile lockfile = lockfileReader.read(ProjectLockfile.in(projectDirectory));
        ClasspathSet classpaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                        lockfile,
                        cacheRoot,
                        artifactIndex).stream()
                .filter(packageFilter)
                .filter(dependency -> dependency.scope().packagedByDefault())
                .toList());
        return buildNativeImage(
                projectDirectory,
                config,
                packageResult,
                classpaths.runtime().entries(),
                nativeImageExecutable,
                progress,
                outputPlan);
    }

    public NativeBuildResult buildNativeImage(
            Path projectDirectory,
            ProjectConfig config,
            PackageResult packageResult,
            List<Path> runtimeClasspath,
            Path nativeImageExecutable) {
        return buildNativeImage(
                projectDirectory,
                config,
                packageResult,
                runtimeClasspath,
                nativeImageExecutable,
                () -> {
                });
    }

    public NativeBuildResult buildNativeImage(
            Path projectDirectory,
            ProjectConfig config,
            PackageResult packageResult,
            List<Path> runtimeClasspath,
            Path nativeImageExecutable,
            Runnable progress) {
        return buildNativeImage(
                projectDirectory,
                config,
                packageResult,
                runtimeClasspath,
                nativeImageExecutable,
                progress,
                NativeOutputPlan.plan(projectDirectory, config, null, nativeImageExecutable));
    }

    private NativeBuildResult buildNativeImage(
            Path projectDirectory,
            ProjectConfig config,
            PackageResult packageResult,
            List<Path> runtimeClasspath,
            Path nativeImageExecutable,
            Runnable progress,
            NativeOutputPlan outputPlan) {
        NativeFrameworkPolicy.rejectUnsupported(config);
        String mainClass = nativeMainClass(config);
        preflightNativeImageExecutable(projectDirectory, nativeImageExecutable);
        NativeSettings nativeSettings = config.nativeSettings().withDefaultImageName(config.project().name());
        Path projectRoot = ProjectPaths.root(projectDirectory);
        List<Path> declaredNativeInputs = new ArrayList<>(runtimeClasspath);
        declaredNativeInputs.add(packageResult.jarPath());
        outputPlan.validateReadInputs(declaredNativeInputs);
        Optional<Path> springBootAotEvidencePath = Optional.empty();
        List<Path> springBootAotClasspath = config.frameworkSettings().springBoot().nativeEnabled()
                ? new SpringBootAotNativeInputs(
                                projectRoot,
                                config.build().outputRoot(),
                                List.of(projectRoot.resolve("zolt.toml"), projectRoot.resolve(config.build().output())))
                        .classpathEntries()
                : List.of();
        if (config.frameworkSettings().springBoot().nativeEnabled()) {
            springBootAotEvidencePath = Optional.of(new SpringBootAotOutputEvidenceService().write(
                    projectRoot,
                    config.build().outputRoot(),
                    outputPlan.evidence()));
        }
        List<Path> nativeRuntimeClasspath =
                new ArrayList<>(NativePackagePolicy.runtimeClasspath(packageResult, runtimeClasspath));
        nativeRuntimeClasspath.addAll(0, springBootAotClasspath);
        NativeImageResult nativeImageResult = nativeImageRunner.build(
                new NativeImageRequest(
                        resolvedNativeImageExecutable(projectRoot, nativeImageExecutable),
                        packageResult.jarPath(),
                        nativeRuntimeClasspath,
                        mainClass,
                        outputPlan.binary(),
                        outputPlan.log(),
                        nativeImageArguments(config, nativeSettings)),
                progress,
                NativeBuildService::reportSeriousWarnings);
        return new NativeBuildResult(packageResult, nativeImageResult, springBootAotEvidencePath);
    }

    private static List<String> nativeImageArguments(ProjectConfig config, NativeSettings nativeSettings) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-J-D" + ProjectVersionOverride.BUILD_PROPERTY + "=" + config.project().version());
        arguments.addAll(nativeSettings.args());
        return List.copyOf(arguments);
    }

    private static String nativeMainClass(ProjectConfig config) {
        return config.project().main().orElseThrow(() -> new NativeImageException(
                "Native Image main class is missing. Add [project].main to zolt.toml."));
    }

    private static void preflightNativeImageExecutable(Path projectDirectory, Path nativeImageExecutable) {
        if (nativeImageExecutable == null || !filesystemPath(nativeImageExecutable)) {
            return;
        }
        Path executable = resolvedNativeImageExecutable(ProjectPaths.root(projectDirectory), nativeImageExecutable);
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new NativeImageException(
                    "Configured Native Image executable is not available at "
                            + nativeImageExecutable
                            + ". Install GraalVM Native Image, put native-image on PATH, or pass `--native-image` with an executable path.");
        }
    }

    private static Path resolvedNativeImageExecutable(Path projectRoot, Path nativeImageExecutable) {
        if (nativeImageExecutable == null || !filesystemPath(nativeImageExecutable)) {
            return nativeImageExecutable;
        }
        Path resolved = nativeImageExecutable.isAbsolute()
                ? nativeImageExecutable
                : projectRoot.resolve(nativeImageExecutable);
        return resolved.toAbsolutePath().normalize();
    }

    private static boolean filesystemPath(Path path) {
        String value = path.toString();
        return path.isAbsolute() || path.getNameCount() > 1 || value.contains("/") || value.contains("\\");
    }

    private static void reportSeriousWarnings(NativeImageResult result) {
        List<String> matches;
        try (var lines = Files.lines(result.logFile())) {
            matches = lines.filter(NativeBuildService::containsSeriousWarningTerm)
                    .limit(21)
                    .toList();
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not inspect Native Image log at "
                            + result.logFile()
                            + " before publishing the native binary.",
                    exception);
        }
        if (!matches.isEmpty()) {
            boolean truncated = matches.size() > 20;
            List<String> reported = truncated ? matches.subList(0, 20) : matches;
            throw new NativeImageException(
                    "Native Image output contains serious warning terms. Review "
                            + result.logFile()
                            + " and fix or document these lines:\n"
                            + String.join("\n", reported)
                            + (truncated ? "\n(additional matching lines omitted)" : ""));
        }
    }

    private static boolean containsSeriousWarningTerm(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return SERIOUS_WARNING_TERMS.stream().anyMatch(lower::contains);
    }
}
