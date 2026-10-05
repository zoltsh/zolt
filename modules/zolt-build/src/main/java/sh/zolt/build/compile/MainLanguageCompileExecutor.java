package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.incremental.GeneratedOutputAttribution;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;

/** Preflights and executes non-javac main-language compilation. */
final class MainLanguageCompileExecutor {
    private final JavacRunner javacRunner;
    private final GroovyCompilerRunner groovyCompilerRunner;
    private final KotlinCompilerRunner kotlinCompilerRunner;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;

    MainLanguageCompileExecutor(
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner,
            KotlinCompilerRunner kotlinCompilerRunner,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder) {
        this.javacRunner = javacRunner;
        this.groovyCompilerRunner = groovyCompilerRunner;
        this.kotlinCompilerRunner = kotlinCompilerRunner;
        this.incrementalCompileStateRecorder = incrementalCompileStateRecorder;
    }

    MainCompilerToolchain legacyToolchain(
            SourceDiscoveryResult sources,
            GroovyCompilerToolchain groovyToolchain) {
        if (!sources.groovyMainSources().isEmpty()) {
            return groovyToolchain == null ? null : MainCompilerToolchain.groovy(groovyToolchain);
        }
        if (!sources.kotlinMainSources().isEmpty()) {
            return null;
        }
        return MainCompilerToolchain.javaOnly();
    }

    Plan preflight(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus,
            MainCompilerToolchain toolchain) {
        if (!sources.kotlinMainSources().isEmpty()) {
            KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                    config, sources, classpaths, jdkStatus);
            KotlinCompilerToolchain kotlinToolchain = requireKotlinToolchain(toolchain);
            requireKaptPlugin(classpaths, kotlinToolchain);
            return Plan.kotlin(options, kotlinToolchain);
        }
        if (!sources.groovyMainSources().isEmpty()) {
            GroovyCompilerRunner.JointOptions options = GroovyJointCompilePolicy.options(
                    config, sources.allMainSources(), classpaths, jdkStatus);
            return Plan.groovy(options, requireGroovyToolchain(toolchain));
        }
        if (toolchain != null && toolchain.language() != MainCompilerToolchain.Language.JAVA) {
            throw new IllegalArgumentException(
                    "Main compiler selection does not match the discovered Java-only main source set.");
        }
        return Plan.javaOnly();
    }

    MainCompileSourceExecutor.Attempt compile(
            Plan plan,
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus) {
        if (!plan.active()) {
            throw new IllegalArgumentException(
                    "Main language compilation requires an active preflight plan.");
        }
        List<Path> compiledSources = sources.allMainSources();
        boolean hostPlatformApi = plan.kotlin()
                ? plan.kotlinOptions().hostPlatformApi()
                : plan.groovyOptions().hostPlatformApi();
        String platformApiWarning = CompilerPlatformApi.determinismWarning(
                hostPlatformApi, "main", jdkStatus);
        incrementalCompileStateRecorder.deleteMainState(outputDirectory);
        CompileOutputCleaner.resetMain(
                projectDirectory, config, outputDirectory, generatedSourcesDirectory);
        JavacResult result = plan.kotlin()
                ? compileKotlinAndJava(
                        sources,
                        classpaths,
                        outputDirectory,
                        generatedSourcesDirectory,
                        jdkStatus,
                        plan)
                : groovyCompilerRunner.compileJoint(
                        jdkStatus.java().orElseThrow(),
                        compiledSources,
                        plan.groovyToolchain().launcherClasspath(),
                        classpaths.compile(),
                        outputDirectory,
                        plan.groovyOptions());
        String fallbackReason = plan.kotlin() ? "kotlin-main-sources" : "groovy-main-sources";
        return MainCompileSourceExecutor.withPlatformApiWarning(
                new MainCompileSourceExecutor.Attempt(
                        result,
                        "full",
                        fallbackReason,
                        CompileDiagnostics.legacy(compiledSources.size(), false),
                        GeneratedOutputAttribution.absent(),
                        compiledSources),
                platformApiWarning);
    }

    private JavacResult compileKotlinAndJava(
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus,
            Plan plan) {
        List<Path> allSources = sources.allMainSources();
        if (!classpaths.processor().entries().isEmpty()) {
            return compileKotlinWithKapt(
                    sources,
                    classpaths,
                    outputDirectory,
                    generatedSourcesDirectory,
                    jdkStatus,
                    plan);
        }
        JavacResult kotlin = kotlinCompilerRunner.compile(
                jdkStatus.java().orElseThrow(),
                jdkStatus.javaHome().orElseThrow(),
                allSources,
                plan.kotlinToolchain().launcherClasspath(),
                classpaths.compile(),
                outputDirectory,
                plan.kotlinOptions());
        if (sources.mainSources().isEmpty()) {
            return kotlin;
        }
        JavacResult java = javacRunner.compile(
                jdkStatus.javac().orElseThrow(),
                sources.mainSources(),
                kotlinJavacClasspath(outputDirectory, classpaths.compile()),
                outputDirectory,
                new Classpath(List.of()),
                null,
                KotlinCompileOptionsPolicy.javacOptions(plan.kotlinOptions()));
        return new JavacResult(
                allSources.size(),
                outputDirectory,
                IncrementalJavacExecution.combinedOutput(kotlin.output(), java.output()));
    }

    private JavacResult compileKotlinWithKapt(
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus,
            Plan plan) {
        createGeneratedSourcesDirectory(generatedSourcesDirectory);
        try (KotlinKaptStubsDirectory stubs = KotlinKaptStubsDirectory.create()) {
            KotlinKaptOptions kaptOptions = new KotlinKaptOptions(
                    plan.kotlinToolchain().kaptPluginJar().orElseThrow(),
                    classpaths.processor(),
                    generatedSourcesDirectory,
                    outputDirectory,
                    stubs.path());
            JavacResult kapt = kotlinCompilerRunner.compile(
                    jdkStatus.java().orElseThrow(),
                    jdkStatus.javaHome().orElseThrow(),
                    sources.allMainSources(),
                    plan.kotlinToolchain().launcherClasspath(),
                    classpaths.compile(),
                    outputDirectory,
                    plan.kotlinOptions(),
                    KotlinCompilationScope.MAIN,
                    kaptOptions);
            List<Path> generatedJavaSources = generatedJavaSources(generatedSourcesDirectory);
            List<Path> kotlinInputs = combinedSources(
                    sources.allMainSources(),
                    generatedJavaSources);
            JavacResult kotlin = kotlinCompilerRunner.compile(
                    jdkStatus.java().orElseThrow(),
                    jdkStatus.javaHome().orElseThrow(),
                    kotlinInputs,
                    plan.kotlinToolchain().launcherClasspath(),
                    classpaths.compile(),
                    outputDirectory,
                    plan.kotlinOptions());
            List<Path> javaInputs = combinedSources(
                    sources.mainSources(),
                    generatedJavaSources);
            JavacResult java = javacRunner.compile(
                    jdkStatus.javac().orElseThrow(),
                    javaInputs,
                    kotlinJavacClasspath(outputDirectory, classpaths.compile()),
                    outputDirectory,
                    new Classpath(List.of()),
                    null,
                    KotlinCompileOptionsPolicy.javacOptions(plan.kotlinOptions()));
            return new JavacResult(
                    sources.allMainSources().size(),
                    outputDirectory,
                    IncrementalJavacExecution.combinedOutput(
                            kapt.output(),
                            IncrementalJavacExecution.combinedOutput(
                                    kotlin.output(), java.output())));
        }
    }

    private static void createGeneratedSourcesDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not create the KAPT generated-source directory " + directory
                            + ". Check that the project directory is writable.",
                    exception);
        }
    }

    private static List<Path> generatedJavaSources(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(Path::normalize)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not inspect KAPT generated Java sources under " + directory
                            + ". Check that the generated-source directory is readable.",
                    exception);
        }
    }

    private static List<Path> combinedSources(
            List<Path> authored,
            List<Path> generated) {
        List<Path> combined = new ArrayList<>(authored.size() + generated.size());
        combined.addAll(authored);
        combined.addAll(generated);
        return List.copyOf(combined);
    }

    private static Classpath kotlinJavacClasspath(
            Path outputDirectory,
            Classpath compileClasspath) {
        List<Path> entries = new ArrayList<>();
        entries.add(outputDirectory);
        entries.addAll(compileClasspath.entries());
        return new Classpath(entries);
    }

    private static GroovyCompilerToolchain requireGroovyToolchain(
            MainCompilerToolchain toolchain) {
        if (toolchain == null
                || toolchain.language() != MainCompilerToolchain.Language.GROOVY
                || toolchain.groovyToolchain().isEmpty()) {
            throw new GroovyCompileException(
                    "Groovy main compilation requires a checksum-verified compiler toolchain before "
                            + "cached output can be reused or compile output can be cleaned. Resolve verified "
                            + "org.apache.groovy:groovy package metadata and retry.");
        }
        return toolchain.groovyToolchain().orElseThrow();
    }

    private static KotlinCompilerToolchain requireKotlinToolchain(
            MainCompilerToolchain toolchain) {
        if (toolchain == null
                || toolchain.language() != MainCompilerToolchain.Language.KOTLIN
                || toolchain.kotlinToolchain().isEmpty()) {
            throw new KotlinCompileException(
                    "Kotlin main compilation requires a checksum-verified compiler toolchain before "
                            + "cached output can be reused or compile output can be cleaned. Resolve verified "
                            + KotlinCompilerToolchain.COORDINATE + " package metadata and retry.");
        }
        return toolchain.kotlinToolchain().orElseThrow();
    }

    private static void requireKaptPlugin(
            ClasspathSet classpaths,
            KotlinCompilerToolchain toolchain) {
        if (!classpaths.processor().entries().isEmpty()
                && toolchain.kaptPluginJar().isEmpty()) {
            throw new KotlinCompileException(
                    "Kotlin main annotation processing requires a checksum-verified "
                            + "org.jetbrains.kotlin:kotlin-annotation-processing-embeddable tool root before "
                            + "cached output can be reused or compile output can be cleaned. Run `zolt resolve` "
                            + "with [dependencies.processor] configured and retry.");
        }
    }

    record Plan(
            GroovyCompilerRunner.JointOptions groovyOptions,
            GroovyCompilerToolchain groovyToolchain,
            KotlinCompilerOptions kotlinOptions,
            KotlinCompilerToolchain kotlinToolchain) {
        static Plan javaOnly() {
            return new Plan(null, null, null, null);
        }

        static Plan groovy(
                GroovyCompilerRunner.JointOptions options,
                GroovyCompilerToolchain toolchain) {
            return new Plan(options, toolchain, null, null);
        }

        static Plan kotlin(
                KotlinCompilerOptions options,
                KotlinCompilerToolchain toolchain) {
            return new Plan(null, null, options, toolchain);
        }

        boolean active() {
            return groovyOptions != null || kotlinOptions != null;
        }

        boolean kotlin() {
            return kotlinOptions != null;
        }
    }
}
