package sh.zolt.build.testruntime.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.build.SourceLanguagePolicy;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.build.compile.KotlinCompilerToolchainResolver;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.generatedsource.ksp.KspGeneratedSourceService;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;

/** Prepares test KSP steps against authored tests, main output, and the ordered test classpath. */
final class KspTestGenerationCoordinator {
    private final SourceDiscoverer sourceDiscoverer;
    private final JdkChecker jdkChecker;
    private final KotlinToolchainVersionResolver kotlinToolchainResolver;
    private final StepGenerator stepGenerator;

    KspTestGenerationCoordinator(SourceDiscoverer sourceDiscoverer, JdkChecker jdkChecker) {
        this(
                sourceDiscoverer,
                jdkChecker,
                KspTestGenerationCoordinator::resolveKotlinVersion,
                new KspGeneratedSourceService()::generate);
    }

    KspTestGenerationCoordinator(
            SourceDiscoverer sourceDiscoverer,
            JdkChecker jdkChecker,
            KotlinToolchainVersionResolver kotlinToolchainResolver,
            StepGenerator stepGenerator) {
        this.sourceDiscoverer = Objects.requireNonNull(sourceDiscoverer, "Source discoverer is required.");
        this.jdkChecker = Objects.requireNonNull(jdkChecker, "JDK checker is required.");
        this.kotlinToolchainResolver = Objects.requireNonNull(
                kotlinToolchainResolver,
                "Kotlin toolchain resolver is required.");
        this.stepGenerator = Objects.requireNonNull(stepGenerator, "KSP step generator is required.");
    }

    void generate(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> packages,
            Path mainOutputDirectory) {
        Objects.requireNonNull(config, "Project configuration is required.");
        List<GeneratedSourceStep> steps = config.build().generatedTestSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.KSP)
                .toList();
        if (steps.isEmpty()) {
            return;
        }
        Path root = Objects.requireNonNull(projectDirectory, "Project directory is required.")
                .toAbsolutePath()
                .normalize();
        ClasspathSet resolvedClasspaths = Objects.requireNonNull(
                classpaths,
                "Resolved classpaths are required for KSP generation.");
        Path mainOutput = Objects.requireNonNull(
                mainOutputDirectory,
                "Main output directory is required for test KSP generation.");
        List<ResolvedClasspathPackage> resolvedPackages = packages == null
                ? List.of()
                : List.copyOf(packages);
        SourceDiscoveryResult sources = sourceDiscoverer.discoverTestBeforeKsp(root, config.build());
        SourceLanguagePolicy.requireTestSupported(sources);
        if (sources.kotlinTestSources().isEmpty()) {
            throw BuildException.actionable(
                    "KSP test generation requires at least one Kotlin test source.",
                    "Add a Kotlin test source to [test.sources].kotlin or remove the KSP step.");
        }
        JdkStatus jdkStatus = jdkChecker.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw BuildException.actionable(
                    "JDK check failed before test KSP generation.",
                    String.join(" ", jdkStatus.problems()));
        }
        String kotlinVersion = kotlinToolchainResolver.resolve(config, resolvedPackages);
        KotlinCompilerOptions compilerOptions = KotlinTestCompilePolicy.options(
                config,
                sources,
                resolvedClasspaths,
                jdkStatus,
                mainOutput);
        List<Path> libraries = new ArrayList<>();
        libraries.add(mainOutput);
        libraries.addAll(resolvedClasspaths.testCompile().entries());
        for (GeneratedSourceStep step : steps) {
            stepGenerator.generate(
                    root,
                    "test",
                    step,
                    resolvedPackages,
                    kotlinVersion,
                    jdkStatus,
                    compilerOptions,
                    sources.kotlinTestSources(),
                    sources.testSources(),
                    List.copyOf(libraries));
        }
    }

    private static String resolveKotlinVersion(
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages) {
        return new KotlinCompilerToolchainResolver().resolve(
                packages,
                config.compilerSettings().kotlinVersion(),
                KotlinCompilationScope.TEST).version();
    }

    @FunctionalInterface
    interface KotlinToolchainVersionResolver {
        String resolve(ProjectConfig config, List<ResolvedClasspathPackage> packages);
    }

    @FunctionalInterface
    interface StepGenerator {
        void generate(
                Path projectRoot,
                String scope,
                GeneratedSourceStep step,
                List<ResolvedClasspathPackage> packages,
                String kotlinVersion,
                JdkStatus jdkStatus,
                KotlinCompilerOptions compilerOptions,
                List<Path> kotlinSourceInputs,
                List<Path> javaSourceInputs,
                List<Path> libraries);
    }
}
