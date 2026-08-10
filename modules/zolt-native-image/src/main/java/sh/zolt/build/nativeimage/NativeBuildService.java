package sh.zolt.build.nativeimage;

import sh.zolt.build.NativeImageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.springboot.SpringBootAotNativeInputs;
import sh.zolt.build.springboot.SpringBootAotOutputEvidenceService;
import sh.zolt.build.springboot.SpringBootNativeBoundaryDiagnostics;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
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
import java.util.stream.Stream;

public final class NativeBuildService {
    private static final List<String> SERIOUS_WARNING_TERMS = List.of("warning", "unsupported", "error");
    private static final String SPRING_BOOT_GROUP = "org.springframework.boot";
    private static final String MICRONAUT_GROUP = "io.micronaut";
    private static final String QUARKUS_GROUP = "io.quarkus";

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
        rejectUnsupportedFrameworkNative(config);
        nativeMainClass(config);
        preflightNativeImageExecutable(nativeImageExecutable);
        PackageResult packageResult = packageService.packageJar(
                projectDirectory,
                nativePackageConfig(config),
                cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(projectDirectory.resolve("zolt.lock"));
        ClasspathSet classpaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot).stream()
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
        rejectUnsupportedFrameworkNative(config);
        String mainClass = nativeMainClass(config);
        preflightNativeImageExecutable(nativeImageExecutable);
        NativeSettings nativeSettings = config.nativeSettings().withDefaultImageName(config.project().name());
        Path projectRoot = ProjectPaths.root(projectDirectory);
        Path outputDirectory = ProjectPaths.output(projectRoot, "[native].output", nativeSettings.output());
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
        List<Path> nativeRuntimeClasspath = new ArrayList<>(packageResult.mode() == PackageMode.UBER
                ? List.of()
                : runtimeClasspath == null ? List.of() : runtimeClasspath);
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

    private static ProjectConfig nativePackageConfig(ProjectConfig config) {
        return config.packageSettings().mode() == PackageMode.UBER
                ? config
                : config.withPackageSettings(PackageSettings.defaults());
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

    private static void rejectUnsupportedFrameworkNative(ProjectConfig config) {
        if (!config.frameworkSettings().springBoot().nativeEnabled() && springBootProject(config)) {
            throw new NativeImageException(
                    "Spring Boot native images require `[framework.springBoot.native] enabled = true`. "
                            + "Zolt supports Spring Boot JVM build, test, run, and executable packaging, "
                            + "and supports an explicit Zolt-owned Spring Boot AOT/native canary path when that flag is enabled. "
                            + "Use `zolt package --mode spring-boot` or `zolt run` for JVM apps, or enable the typed Spring Boot native path.");
        }
        if (config.frameworkSettings().springBoot().nativeEnabled()) {
            rejectUnsupportedSpringBootNativeBaseline(config);
        }
        if (micronautProject(config)) {
            throw new NativeImageException(
                    "Micronaut native images are not supported by Zolt yet. "
                            + "Zolt supports basic Micronaut JVM build/test flows through Java annotation processors, "
                            + "but does not run Micronaut AOT or framework-native processing in the public beta. "
                            + "Use `zolt build`, `zolt test`, or `zolt package --mode thin` for the current beta path.");
        }
        if (quarkusProject(config)) {
            throw new NativeImageException(
                    "Quarkus native images are not supported by Zolt yet. "
                            + "Zolt supports the experimental Quarkus JVM build/test/package path, "
                            + "but does not run Quarkus native augmentation, dev mode, or advanced native modes in the public beta. "
                            + "Use `zolt package --mode quarkus` or `zolt run` for the current beta path.");
        }
    }

    private static void rejectUnsupportedSpringBootNativeBaseline(ProjectConfig config) {
        if (!"21".equals(config.project().java())) {
            throw new NativeImageException(
                    "Spring Boot native support is currently proven for Java 21 projects. Found [project].java = "
                            + config.project().java()
                            + ". Set [project].java to 21 or use `zolt package --mode spring-boot` for the JVM Spring Boot path.");
        }
        springBootVersion(config).ifPresent(version -> {
            if (!version.startsWith("3.3.")) {
                throw new NativeImageException(
                        "Spring Boot native support is currently proven for Spring Boot 3.3 on Java 21. Found Spring Boot "
                                + version
                                + ". Use Spring Boot 3.3 or keep this project on the JVM Spring Boot path until this baseline has executable smoke evidence.");
            }
        });
        SpringBootNativeBoundaryDiagnostics.rejectUnsupportedEcosystem(config);
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

    private static boolean springBootProject(ProjectConfig config) {
        PackageMode packageMode = config.packageSettings().mode();
        if (packageMode == PackageMode.SPRING_BOOT || packageMode == PackageMode.SPRING_BOOT_WAR) {
            return true;
        }
        if (containsSpringBootCoordinate(config.platforms().keySet())) {
            return true;
        }
        return containsSpringBootCoordinate(config.apiDependencies().keySet())
                || containsSpringBootCoordinate(config.managedApiDependencies())
                || containsSpringBootCoordinate(config.dependencies().keySet())
                || containsSpringBootCoordinate(config.managedDependencies())
                || containsSpringBootCoordinate(config.runtimeDependencies().keySet())
                || containsSpringBootCoordinate(config.managedRuntimeDependencies())
                || containsSpringBootCoordinate(config.providedDependencies().keySet())
                || containsSpringBootCoordinate(config.managedProvidedDependencies())
                || containsSpringBootCoordinate(config.devDependencies().keySet())
                || containsSpringBootCoordinate(config.managedDevDependencies())
                || containsSpringBootCoordinate(config.testDependencies().keySet())
                || containsSpringBootCoordinate(config.managedTestDependencies())
                || containsSpringBootCoordinate(config.annotationProcessors().keySet())
                || containsSpringBootCoordinate(config.managedAnnotationProcessors())
                || containsSpringBootCoordinate(config.testAnnotationProcessors().keySet())
                || containsSpringBootCoordinate(config.managedTestAnnotationProcessors());
    }

    private static Optional<String> springBootVersion(ProjectConfig config) {
        String platformVersion = config.platforms().get("org.springframework.boot:spring-boot-dependencies");
        if (platformVersion != null && !platformVersion.isBlank()) {
            return Optional.of(platformVersion);
        }
        return Stream.of(
                        config.apiDependencies(),
                        config.dependencies(),
                        config.runtimeDependencies(),
                        config.providedDependencies(),
                        config.devDependencies(),
                        config.testDependencies(),
                        config.annotationProcessors(),
                        config.testAnnotationProcessors())
                .flatMap(map -> map.entrySet().stream())
                .filter(entry -> entry.getKey().startsWith(SPRING_BOOT_GROUP + ":"))
                .map(java.util.Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static boolean micronautProject(ProjectConfig config) {
        return containsMicronautCoordinate(config.platforms().keySet())
                || containsMicronautCoordinate(config.apiDependencies().keySet())
                || containsMicronautCoordinate(config.managedApiDependencies())
                || containsMicronautCoordinate(config.dependencies().keySet())
                || containsMicronautCoordinate(config.managedDependencies())
                || containsMicronautCoordinate(config.runtimeDependencies().keySet())
                || containsMicronautCoordinate(config.managedRuntimeDependencies())
                || containsMicronautCoordinate(config.providedDependencies().keySet())
                || containsMicronautCoordinate(config.managedProvidedDependencies())
                || containsMicronautCoordinate(config.devDependencies().keySet())
                || containsMicronautCoordinate(config.managedDevDependencies())
                || containsMicronautCoordinate(config.testDependencies().keySet())
                || containsMicronautCoordinate(config.managedTestDependencies())
                || containsMicronautCoordinate(config.annotationProcessors().keySet())
                || containsMicronautCoordinate(config.managedAnnotationProcessors())
                || containsMicronautCoordinate(config.testAnnotationProcessors().keySet())
                || containsMicronautCoordinate(config.managedTestAnnotationProcessors());
    }

    private static boolean quarkusProject(ProjectConfig config) {
        return config.packageSettings().mode() == PackageMode.QUARKUS || config.frameworkSettings().quarkus().enabled();
    }

    private static boolean containsSpringBootCoordinate(Iterable<String> coordinates) {
        return containsGroupCoordinate(coordinates, SPRING_BOOT_GROUP);
    }

    private static boolean containsMicronautCoordinate(Iterable<String> coordinates) {
        return containsGroupCoordinate(coordinates, MICRONAUT_GROUP);
    }

    private static boolean containsQuarkusCoordinate(Iterable<String> coordinates) {
        return containsGroupCoordinate(coordinates, QUARKUS_GROUP);
    }

    private static boolean containsGroupCoordinate(Iterable<String> coordinates, String group) {
        for (String coordinate : coordinates) {
            if (coordinate != null && coordinate.startsWith(group + ":")) {
                return true;
            }
        }
        return false;
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
