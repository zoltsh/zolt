package sh.zolt.build.nativeimage;

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
        preflightNativeImageExecutable(nativeImageExecutable);
        nativeOutputDirectory(projectDirectory, config);
        ProjectConfig packageConfig = NativePackagePolicy.packageConfig(config);
        var packageFilter = NativePackagePolicy.classpathFilter(config);
        PackageResult packageResult = packageService.packageJar(
                projectDirectory,
                config,
                packageConfig,
                cacheRoot,
                artifactIndex);
        ZoltLockfile lockfile = lockfileReader.read(projectDirectory.resolve("zolt.lock"));
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
                progress);
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
        NativeFrameworkPolicy.rejectUnsupported(config);
        String mainClass = nativeMainClass(config);
        preflightNativeImageExecutable(nativeImageExecutable);
        NativeSettings nativeSettings = config.nativeSettings().withDefaultImageName(config.project().name());
        Path projectRoot = ProjectPaths.root(projectDirectory);
        Path outputDirectory = nativeOutputDirectory(projectRoot, config);
        String imageName = ProjectPaths.filenameComponent("[native].imageName", nativeSettings.imageName());
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
                    outputDirectory.resolve("spring-aot-evidence.json")));
        }
        List<Path> nativeRuntimeClasspath =
                new ArrayList<>(NativePackagePolicy.runtimeClasspath(packageResult, runtimeClasspath));
        nativeRuntimeClasspath.addAll(0, springBootAotClasspath);
        NativeImageResult nativeImageResult = nativeImageRunner.build(new NativeImageRequest(
                nativeImageExecutable,
                packageResult.jarPath(),
                nativeRuntimeClasspath,
                mainClass,
                outputDirectory.resolve(imageName),
                outputDirectory.resolve("native-image.log"),
                nativeImageArguments(config, nativeSettings)), progress);
        reportSeriousWarnings(nativeImageResult);
        return new NativeBuildResult(packageResult, nativeImageResult, springBootAotEvidencePath);
    }

    private static List<String> nativeImageArguments(ProjectConfig config, NativeSettings nativeSettings) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-J-D" + ProjectVersionOverride.BUILD_PROPERTY + "=" + config.project().version());
        arguments.addAll(nativeSettings.args());
        return List.copyOf(arguments);
    }

    private static Path nativeOutputDirectory(Path projectDirectory, ProjectConfig config) {
        Path projectRoot = ProjectPaths.root(projectDirectory);
        return ProjectPaths.output(
                projectRoot,
                "[native].output",
                config.nativeSettings().output());
    }

    private static String nativeMainClass(ProjectConfig config) {
        return config.project().main().orElseThrow(() -> new NativeImageException(
                "Native Image main class is missing. Add [project].main to zolt.toml."));
    }

    private static void preflightNativeImageExecutable(Path nativeImageExecutable) {
        if (nativeImageExecutable == null || !filesystemPath(nativeImageExecutable)) {
            return;
        }
        Path executable = nativeImageExecutable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new NativeImageException(
                    "Configured Native Image executable is not available at "
                            + nativeImageExecutable
                            + ". Install GraalVM Native Image, put native-image on PATH, or pass `--native-image` with an executable path.");
        }
    }

    private static boolean filesystemPath(Path path) {
        String value = path.toString();
        return path.isAbsolute() || path.getNameCount() > 1 || value.contains("/") || value.contains("\\");
    }

    private static void reportSeriousWarnings(NativeImageResult result) {
        List<String> matches = result.output().lines()
                .filter(NativeBuildService::containsSeriousWarningTerm)
                .toList();
        if (!matches.isEmpty()) {
            throw new NativeImageException(
                    "Native Image output contains serious warning terms. Review "
                            + result.logFile()
                            + " and fix or document these lines:\n"
                            + String.join("\n", matches));
        }
    }

    private static boolean containsSeriousWarningTerm(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return SERIOUS_WARNING_TERMS.stream().anyMatch(lower::contains);
    }
}
