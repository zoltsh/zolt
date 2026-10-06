package sh.zolt.build.generatedsource.ksp;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.build.SourceLanguagePolicy;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompileOptionsPolicy;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.build.compile.MainCompilerToolchain;
import sh.zolt.build.compile.MainCompilerToolchainResolver;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;

/** Prepares and executes every main KSP step against the exact pre-KSP source universe. */
public final class KspMainGenerationCoordinator {
    private final SourceDiscoverer sourceDiscoverer;
    private final JdkChecker jdkChecker;
    private final KotlinToolchainVersionResolver kotlinToolchainResolver;
    private final StepGenerator stepGenerator;

    public KspMainGenerationCoordinator(
            SourceDiscoverer sourceDiscoverer,
            JdkChecker jdkChecker) {
        this(
                sourceDiscoverer,
                jdkChecker,
                KspMainGenerationCoordinator::resolveKotlinVersion,
                new KspGeneratedSourceService()::generate);
    }

    KspMainGenerationCoordinator(
            SourceDiscoverer sourceDiscoverer,
            JdkChecker jdkChecker,
            KotlinToolchainVersionResolver kotlinToolchainResolver,
            StepGenerator stepGenerator) {
        this.sourceDiscoverer = Objects.requireNonNull(
                sourceDiscoverer,
                "Source discoverer is required.");
        this.jdkChecker = Objects.requireNonNull(jdkChecker, "JDK checker is required.");
        this.kotlinToolchainResolver = Objects.requireNonNull(
                kotlinToolchainResolver,
                "Kotlin toolchain resolver is required.");
        this.stepGenerator = Objects.requireNonNull(
                stepGenerator,
                "KSP step generator is required.");
    }

    public void generate(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> packages) {
        Objects.requireNonNull(config, "Project configuration is required.");
        List<GeneratedSourceStep> steps = config.build().generatedMainSources().stream()
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
        List<ResolvedClasspathPackage> resolvedPackages = packages == null
                ? List.of()
                : List.copyOf(packages);
        SourceDiscoveryResult sources = sourceDiscoverer.discoverMainBeforeKsp(
                root,
                config.build());
        SourceLanguagePolicy.requireMainSupported(sources);
        if (sources.kotlinMainSources().isEmpty()) {
            throw BuildException.actionable(
                    "KSP main generation requires at least one Kotlin main source.",
                    "Add a Kotlin main source to [build].sources or remove the KSP step.");
        }
        JdkStatus jdkStatus = jdkChecker.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw BuildException.actionable(
                    "JDK check failed before KSP generation.",
                    String.join(" ", jdkStatus.problems()));
        }
        String kotlinVersion = kotlinToolchainResolver.resolve(
                sources,
                config,
                resolvedPackages);
        KotlinCompilerOptions compilerOptions = KotlinCompileOptionsPolicy.options(
                config,
                jdkStatus,
                KotlinCompilationScope.MAIN);
        for (GeneratedSourceStep step : steps) {
            // The standalone KSP CLI accepts List<File>. Exact discovered files avoid feeding a broad
            // authored root such as `.` (and therefore target/ or an earlier KSP output) back into KSP.
            stepGenerator.generate(
                    root,
                    "main",
                    step,
                    resolvedPackages,
                    kotlinVersion,
                    jdkStatus,
                    compilerOptions,
                    sources.kotlinMainSources(),
                    sources.mainSources(),
                    resolvedClasspaths.compile().entries());
        }
    }

    private static String resolveKotlinVersion(
            SourceDiscoveryResult sources,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages) {
        MainCompilerToolchain toolchain = new MainCompilerToolchainResolver().resolve(
                sources,
                config,
                packages,
                true);
        if (toolchain.language() != MainCompilerToolchain.Language.KOTLIN) {
            throw BuildException.actionable(
                    "KSP main generation requires the Kotlin main compiler toolchain.",
                    "Add a Kotlin main source and configure the Kotlin toolchain, or remove the KSP step.");
        }
        return toolchain.kotlinToolchain().orElseThrow().version();
    }

    @FunctionalInterface
    interface KotlinToolchainVersionResolver {
        String resolve(
                SourceDiscoveryResult sources,
                ProjectConfig config,
                List<ResolvedClasspathPackage> packages);
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
