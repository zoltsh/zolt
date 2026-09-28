package sh.zolt.build.testruntime.compile;

import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.compile.CompileOutputCleaner;
import sh.zolt.build.compile.CompilerPlatformApi;
import sh.zolt.build.compile.EffectiveCompilerIdentity;
import sh.zolt.build.compile.GroovyCompilerRunner;
import sh.zolt.build.JavacException;
import sh.zolt.build.compile.IncrementalJavacExecution;
import sh.zolt.build.compile.JavacOptions;
import sh.zolt.build.compile.JavacResult;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.incremental.GeneratedOutputAttribution;
import sh.zolt.build.incremental.IncrementalCompilePlan;
import sh.zolt.build.incremental.IncrementalCompilePlanner;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.build.incremental.IncrementalCompileWaveResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.List;

final class TestCompileSourceExecutor {
    private final JavacRunner javacRunner;
    private final GroovyCompilerRunner groovyCompilerRunner;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final IncrementalCompilePlanner incrementalCompilePlanner;
    private final IncrementalJavacExecution incrementalJavacExecution;

    TestCompileSourceExecutor(
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder,
            IncrementalCompilePlanner incrementalCompilePlanner) {
        this.javacRunner = javacRunner;
        this.groovyCompilerRunner = groovyCompilerRunner;
        this.incrementalCompileStateRecorder = incrementalCompileStateRecorder;
        this.incrementalCompilePlanner = incrementalCompilePlanner;
        this.incrementalJavacExecution = new IncrementalJavacExecution(javacRunner, incrementalCompilePlanner);
    }

    Attempt compile(
            boolean compileSkipped,
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Classpath testCompileClasspath,
            Classpath groovyCompileClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus) {
        if (compileSkipped) {
            return new Attempt(
                    new JavacResult(sources.testSources().size(), outputDirectory, ""),
                    new JavacResult(sources.groovyTestSources().size(), outputDirectory, ""),
                    "skipped",
                    "",
                    CompileDiagnostics.empty());
        }
        boolean hostMode = config.compilerSettings().testHostPlatformApi()
                && !effectiveRelease(config).isBlank();
        CompilerPlatformApi.rejectModularHost(hostMode, sources.testSources(), "test");
        JavacOptions options = javacOptions(config, hostMode);
        String platformApiWarning = CompilerPlatformApi.determinismWarning(hostMode, "test", jdkStatus);
        IncrementalCompilePlan plan = incrementalCompilePlanner.planTest(
                projectDirectory,
                config,
                sources,
                testCompileClasspath,
                classpaths.testProcessor(),
                outputDirectory,
                generatedSourcesDirectory,
                EffectiveCompilerIdentity.of(jdkStatus));
        if (plan.incremental()) {
            return withPlatformApiWarning(
                    incrementalCompile(
                            projectDirectory,
                            config,
                            jdkStatus,
                            sources,
                            testCompileClasspath,
                            groovyCompileClasspath,
                            outputDirectory,
                            generatedSourcesDirectory,
                            classpaths,
                            options,
                            plan),
                    platformApiWarning);
        }
        incrementalCompileStateRecorder.deleteTestState(outputDirectory);
        return withPlatformApiWarning(
                fullTestCompile(
                        projectDirectory,
                        config,
                        jdkStatus,
                        sources,
                        testCompileClasspath,
                        groovyCompileClasspath,
                        outputDirectory,
                        generatedSourcesDirectory,
                        classpaths,
                        options,
                        plan.fallbackReason(),
                        plan.fullDiagnostics(sources.testSources().size() + sources.groovyTestSources().size()),
                        plan.captureProcessorAttribution()),
                platformApiWarning);
    }

    private static Attempt withPlatformApiWarning(Attempt attempt, String warning) {
        if (warning == null || warning.isBlank()) {
            return attempt;
        }
        JavacResult javacResult = attempt.javacResult();
        return new Attempt(
                new JavacResult(
                        javacResult.sourceCount(),
                        javacResult.outputDirectory(),
                        IncrementalJavacExecution.combinedOutput(warning, javacResult.output())),
                attempt.groovyResult(),
                attempt.mode(),
                attempt.fallbackReason(),
                attempt.diagnostics(),
                attempt.attribution(),
                attempt.compiledSources());
    }

    private Attempt incrementalCompile(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath groovyCompileClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            ClasspathSet classpaths,
            JavacOptions options,
            IncrementalCompilePlan plan) {
        IncrementalJavacExecution.Result execution;
        try {
            execution = incrementalJavacExecution.run(
                    jdkStatus.javac().orElseThrow(),
                    plan,
                    testCompileClasspath,
                    outputDirectory,
                    classpaths.testProcessor(),
                    generatedSourcesDirectory,
                    options);
        } catch (JavacException exception) {
            incrementalCompileStateRecorder.deleteTestState(outputDirectory);
            return fullTestCompile(
                    projectDirectory,
                    config,
                    jdkStatus,
                    sources,
                    testCompileClasspath,
                    groovyCompileClasspath,
                    outputDirectory,
                    generatedSourcesDirectory,
                    classpaths,
                    options,
                    "incremental-javac-failed",
                    plan.fullDiagnostics(sources.testSources().size() + sources.groovyTestSources().size()),
                    plan.captureProcessorAttribution());
        }
        IncrementalCompileWaveResult waves = execution.waves();
        GeneratedOutputAttribution attribution = execution.attribution();
        if (waves.hasFallback()) {
            return fullTestFallback(
                    projectDirectory,
                    config,
                    jdkStatus,
                    sources,
                    testCompileClasspath,
                    groovyCompileClasspath,
                    outputDirectory,
                    generatedSourcesDirectory, classpaths, options, plan, waves.validation().fallbackReason());
        }
        if (attribution.present() && attribution.unattributed()) {
            return fullTestFallback(
                    projectDirectory,
                    config,
                    jdkStatus,
                    sources,
                    testCompileClasspath,
                    groovyCompileClasspath,
                    outputDirectory,
                    generatedSourcesDirectory, classpaths, options, plan, "processor-unattributed-output");
        }
        JavacResult combined = new JavacResult(
                execution.primary().sourceCount() + waves.dependentSourceCount(),
                outputDirectory,
                IncrementalJavacExecution.combinedOutput(execution.primary().output(), waves.dependentOutput()));
        return new Attempt(
                combined,
                new JavacResult(0, outputDirectory, ""),
                "incremental",
                "",
                plan.diagnostics(combined.sourceCount(), waves.validation()),
                attribution,
                execution.compiledSources());
    }

    private Attempt fullTestFallback(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath groovyCompileClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            ClasspathSet classpaths,
            JavacOptions options,
            IncrementalCompilePlan plan,
            String fallbackReason) {
        incrementalCompileStateRecorder.deleteTestState(outputDirectory);
        return fullTestCompile(
                projectDirectory,
                config,
                jdkStatus,
                sources,
                testCompileClasspath,
                groovyCompileClasspath,
                outputDirectory,
                generatedSourcesDirectory,
                classpaths,
                options,
                fallbackReason,
                plan.fullDiagnostics(sources.testSources().size() + sources.groovyTestSources().size()),
                plan.captureProcessorAttribution());
    }

    private Attempt fullTestCompile(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath groovyCompileClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            ClasspathSet classpaths,
            JavacOptions options,
            String fallbackReason,
            CompileDiagnostics diagnostics,
            boolean captureAttribution) {
        CompileOutputCleaner.resetTest(
                projectDirectory, config, outputDirectory, generatedSourcesDirectory);
        JavacResult javacResult = javacRunner.compile(
                jdkStatus.javac().orElseThrow(),
                sources.testSources(),
                testCompileClasspath,
                outputDirectory,
                classpaths.testProcessor(),
                generatedSourcesDirectory,
                options,
                captureAttribution);
        JavacResult groovyResult = groovyCompilerRunner.compile(
                jdkStatus.java().orElseThrow(),
                sources.groovyTestSources(),
                groovyCompileClasspath,
                outputDirectory);
        return new Attempt(
                javacResult,
                groovyResult,
                "full",
                fallbackReason,
                diagnostics,
                javacResult.attribution(),
                sources.testSources());
    }

    private static JavacOptions javacOptions(ProjectConfig config, boolean hostMode) {
        CompilerSettings compiler = config.compilerSettings();
        return new JavacOptions(
                effectiveRelease(config),
                compiler.encoding(),
                compiler.testArgs(),
                List.of(),
                hostMode);
    }

    private static String effectiveRelease(ProjectConfig config) {
        String compilerRelease = config.compilerSettings().release();
        return compilerRelease.isBlank() ? config.project().java() : compilerRelease;
    }

    record Attempt(JavacResult javacResult,
            JavacResult groovyResult,
            String mode,
            String fallbackReason,
            CompileDiagnostics diagnostics,
            GeneratedOutputAttribution attribution,
            List<Path> compiledSources) {
        Attempt(JavacResult javacResult,
                JavacResult groovyResult,
                String mode,
                String fallbackReason,
                CompileDiagnostics diagnostics) {
            this(javacResult, groovyResult, mode, fallbackReason, diagnostics,
                    GeneratedOutputAttribution.absent(), List.of());
        }

        int sourceCount() {
            return javacResult.sourceCount() + groovyResult.sourceCount();
        }

        Path outputDirectory() {
            return javacResult.outputDirectory();
        }

        String output() {
            return IncrementalJavacExecution.combinedOutput(javacResult.output(), groovyResult.output());
        }
    }
}
