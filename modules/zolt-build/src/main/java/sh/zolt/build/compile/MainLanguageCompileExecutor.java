package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.kotlin.kapt.KotlinKaptCompileExecutor;
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
    private final KotlinKaptCompileExecutor kotlinKaptCompileExecutor;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;

    MainLanguageCompileExecutor(
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner,
            KotlinCompilerRunner kotlinCompilerRunner,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder) {
        this.javacRunner = javacRunner;
        this.groovyCompilerRunner = groovyCompilerRunner;
        this.kotlinCompilerRunner = kotlinCompilerRunner;
        this.kotlinKaptCompileExecutor = new KotlinKaptCompileExecutor(
                javacRunner,
                kotlinCompilerRunner);
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
                plan.kotlinOptions(),
                KotlinCompilationScope.MAIN,
                null,
                plan.kotlinToolchain().compilerPluginJars(),
                plan.kotlinToolchain().compilerPluginOptions());
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
        return kotlinKaptCompileExecutor.compile(
                jdkStatus,
                sources.allMainSources(),
                sources.mainSources(),
                plan.kotlinToolchain().invocationToolchain(),
                classpaths.compile(),
                classpaths.processor(),
                outputDirectory,
                generatedSourcesDirectory,
                plan.kotlinOptions(),
                KotlinCompilationScope.MAIN);
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
