package sh.zolt.build.testruntime.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.JavacOptions;
import sh.zolt.build.compile.JavacResult;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompileOptionsPolicy;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.build.compile.KotlinCompilerRunner;
import sh.zolt.build.compile.kotlin.KotlinCompilerInvocationToolchain;
import sh.zolt.build.compile.kotlin.kapt.KotlinKaptCompileExecutor;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;

/** Executes bounded Kotlin/JVM test compilation, including the shared KAPT lifecycle. */
final class KotlinTestCompileExecutor {
    private final JavaPhase javaPhase;
    private final KotlinPhase kotlinPhase;
    private final KaptPhase kaptPhase;

    KotlinTestCompileExecutor(
            JavacRunner javacRunner,
            KotlinCompilerRunner kotlinCompilerRunner) {
        this(
                javacRunner::compile,
                (java, jdkHome, sources, toolchain, classpath, output, options, scope) ->
                        kotlinCompilerRunner.compile(
                                java,
                                jdkHome,
                                sources,
                                toolchain.launcherClasspath(),
                                classpath,
                                output,
                                options,
                                scope,
                                null,
                                toolchain.compilerPluginJars()),
                new KotlinKaptCompileExecutor(javacRunner, kotlinCompilerRunner)::compile);
    }

    KotlinTestCompileExecutor(JavaPhase javaPhase, KotlinPhase kotlinPhase) {
        this(javaPhase, kotlinPhase, (jdk, allSources, javaSources, toolchain, classpath, processors,
                output, generated, options, scope) -> {
            throw new AssertionError("KAPT phase must not run");
        });
    }

    KotlinTestCompileExecutor(
            JavaPhase javaPhase,
            KotlinPhase kotlinPhase,
            KaptPhase kaptPhase) {
        this.javaPhase = javaPhase;
        this.kotlinPhase = kotlinPhase;
        this.kaptPhase = kaptPhase;
    }

    TestCompileAttempt compile(
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath kotlinCompilerLauncherClasspath,
            KotlinCompilerOptions kotlinOptions,
            Path outputDirectory,
            String fallbackReason,
            CompileDiagnostics diagnostics) {
        return compile(
                jdkStatus,
                sources,
                testCompileClasspath,
                new KotlinCompilerInvocationToolchain(
                        kotlinCompilerLauncherClasspath,
                        null,
                        List.of()),
                new Classpath(List.of()),
                kotlinOptions,
                outputDirectory,
                null,
                fallbackReason,
                diagnostics);
    }

    TestCompileAttempt compile(
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath kotlinCompilerLauncherClasspath,
            Classpath processorClasspath,
            Path kaptPluginJar,
            KotlinCompilerOptions kotlinOptions,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            String fallbackReason,
            CompileDiagnostics diagnostics) {
        return compile(
                jdkStatus,
                sources,
                testCompileClasspath,
                new KotlinCompilerInvocationToolchain(
                        kotlinCompilerLauncherClasspath,
                        kaptPluginJar,
                        List.of()),
                processorClasspath,
                kotlinOptions,
                outputDirectory,
                generatedSourcesDirectory,
                fallbackReason,
                diagnostics);
    }

    TestCompileAttempt compile(
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            KotlinCompilerInvocationToolchain kotlinCompilerToolchain,
            Classpath processorClasspath,
            KotlinCompilerOptions kotlinOptions,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            String fallbackReason,
            CompileDiagnostics diagnostics) {
        List<Path> allSources = sources.allTestSources();
        if (!processorClasspath.entries().isEmpty()) {
            if (kotlinCompilerToolchain.kaptPluginJar().isEmpty()) {
                throw new KotlinCompileException(
                        "Kotlin test annotation processing requires a verified KAPT compiler plugin.");
            }
            JavacResult result = kaptPhase.compile(
                    jdkStatus,
                    allSources,
                    sources.testSources(),
                    kotlinCompilerToolchain,
                    testCompileClasspath,
                    processorClasspath,
                    outputDirectory,
                    generatedSourcesDirectory,
                    kotlinOptions,
                    KotlinCompilationScope.TEST);
            return new TestCompileAttempt(
                    new JavacResult(0, outputDirectory, ""),
                    new JavacResult(0, outputDirectory, ""),
                    new JavacResult(allSources.size(), outputDirectory, result.output()),
                    "full",
                    fallbackReason,
                    diagnostics,
                    result.attribution(),
                    allSources);
        }
        JavacResult kotlinPhaseResult = kotlinPhase.compile(
                jdkStatus.java().orElseThrow(),
                jdkStatus.javaHome().orElseThrow(),
                allSources,
                kotlinCompilerToolchain,
                testCompileClasspath,
                outputDirectory,
                kotlinOptions,
                KotlinCompilationScope.TEST);
        JavacResult kotlinResult = new JavacResult(
                sources.kotlinTestSources().size(),
                outputDirectory,
                kotlinPhaseResult.output());
        JavacResult javacResult = sources.testSources().isEmpty()
                ? new JavacResult(0, outputDirectory, "")
                : javaPhase.compile(
                        jdkStatus.javac().orElseThrow(),
                        sources.testSources(),
                        javacClasspath(outputDirectory, testCompileClasspath),
                        outputDirectory,
                        new Classpath(List.of()),
                        null,
                        KotlinCompileOptionsPolicy.javacOptions(kotlinOptions));
        return new TestCompileAttempt(
                javacResult,
                new JavacResult(0, outputDirectory, ""),
                kotlinResult,
                "full",
                fallbackReason,
                diagnostics,
                javacResult.attribution(),
                allSources);
    }

    private static Classpath javacClasspath(
            Path outputDirectory,
            Classpath testCompileClasspath) {
        List<Path> entries = new ArrayList<>();
        entries.add(outputDirectory);
        entries.addAll(testCompileClasspath.entries());
        return new Classpath(entries);
    }

    @FunctionalInterface
    interface JavaPhase {
        JavacResult compile(
                Path javac,
                List<Path> sources,
                Classpath classpath,
                Path outputDirectory,
                Classpath processorClasspath,
                Path generatedSourcesDirectory,
                JavacOptions options);
    }

    @FunctionalInterface
    interface KotlinPhase {
        JavacResult compile(
                Path java,
                Path jdkHome,
                List<Path> sources,
                KotlinCompilerInvocationToolchain compilerToolchain,
                Classpath compilationClasspath,
                Path outputDirectory,
                KotlinCompilerOptions options,
                KotlinCompilationScope scope);
    }

    @FunctionalInterface
    interface KaptPhase {
        JavacResult compile(
                JdkStatus jdkStatus,
                List<Path> allSources,
                List<Path> javaSources,
                KotlinCompilerInvocationToolchain compilerToolchain,
                Classpath compilationClasspath,
                Classpath processorClasspath,
                Path outputDirectory,
                Path generatedSourcesDirectory,
                KotlinCompilerOptions options,
                KotlinCompilationScope scope);
    }
}
