package sh.zolt.build.testruntime.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.compile.JavacOptions;
import sh.zolt.build.compile.JavacResult;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompileOptionsPolicy;
import sh.zolt.build.compile.KotlinCompilerRunner;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;

/** Executes the bounded Kotlin-first, javac-second test compilation contract. */
final class KotlinTestCompileExecutor {
    private final JavaPhase javaPhase;
    private final KotlinPhase kotlinPhase;

    KotlinTestCompileExecutor(
            JavacRunner javacRunner,
            KotlinCompilerRunner kotlinCompilerRunner) {
        this(javacRunner::compile, kotlinCompilerRunner::compile);
    }

    KotlinTestCompileExecutor(JavaPhase javaPhase, KotlinPhase kotlinPhase) {
        this.javaPhase = javaPhase;
        this.kotlinPhase = kotlinPhase;
    }

    TestCompileAttempt compile(
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath kotlinCompilerLauncherClasspath,
            KotlinCompilerRunner.Options kotlinOptions,
            Path outputDirectory,
            String fallbackReason,
            CompileDiagnostics diagnostics) {
        List<Path> allSources = sources.allTestSources();
        JavacResult kotlinPhaseResult = kotlinPhase.compile(
                jdkStatus.java().orElseThrow(),
                jdkStatus.javaHome().orElseThrow(),
                allSources,
                kotlinCompilerLauncherClasspath,
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
                Classpath compilerLauncherClasspath,
                Classpath compilationClasspath,
                Path outputDirectory,
                KotlinCompilerRunner.Options options,
                KotlinCompilationScope scope);
    }
}
