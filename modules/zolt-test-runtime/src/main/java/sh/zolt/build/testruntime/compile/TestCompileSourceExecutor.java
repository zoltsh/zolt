package sh.zolt.build.testruntime.compile;

import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.compile.CompileOutputCleaner;
import sh.zolt.build.compile.CompilerPlatformApi;
import sh.zolt.build.compile.GroovyCompilerRunner;
import sh.zolt.build.JavacException;
import sh.zolt.build.compile.IncrementalJavacExecution;
import sh.zolt.build.compile.JavacOptions;
import sh.zolt.build.compile.JavacResult;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.compile.KotlinCompilerRunner;
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
    private final KotlinTestCompileExecutor kotlinTestCompileExecutor;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final IncrementalCompilePlanner incrementalCompilePlanner;
    private final IncrementalJavacExecution incrementalJavacExecution;

    TestCompileSourceExecutor(
            JavacRunner javacRunner,
            GroovyCompilerRunner groovyCompilerRunner,
            KotlinCompilerRunner kotlinCompilerRunner,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder,
            IncrementalCompilePlanner incrementalCompilePlanner) {
        this.javacRunner = javacRunner;
        this.groovyCompilerRunner = groovyCompilerRunner;
        this.kotlinTestCompileExecutor = new KotlinTestCompileExecutor(javacRunner, kotlinCompilerRunner);
        this.incrementalCompileStateRecorder = incrementalCompileStateRecorder;
        this.incrementalCompilePlanner = incrementalCompilePlanner;
        this.incrementalJavacExecution = new IncrementalJavacExecution(javacRunner, incrementalCompilePlanner);
    }

    TestCompileAttempt compile(
            boolean compileSkipped,
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Classpath testCompileClasspath,
            Classpath groovyCompilerLauncherClasspath,
            Classpath groovyCompileClasspath,
            Classpath kotlinCompilerLauncherClasspath,
            KotlinCompilerRunner.Options kotlinOptions,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus,
            String compilerIdentity) {
        if (compileSkipped) {
            return new TestCompileAttempt(
                    new JavacResult(sources.testSources().size(), outputDirectory, ""),
                    new JavacResult(sources.groovyTestSources().size(), outputDirectory, ""),
                    new JavacResult(sources.kotlinTestSources().size(), outputDirectory, ""),
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
                compilerIdentity);
        if (plan.incremental()) {
            return incrementalCompile(
                            projectDirectory,
                            config,
                            jdkStatus,
                            sources,
                            testCompileClasspath,
                            groovyCompilerLauncherClasspath,
                            groovyCompileClasspath,
                            kotlinCompilerLauncherClasspath,
                            kotlinOptions,
                            outputDirectory,
                            generatedSourcesDirectory,
                            classpaths,
                            options,
                            plan)
                    .withPlatformApiWarning(platformApiWarning);
        }
        incrementalCompileStateRecorder.deleteTestState(outputDirectory);
        return fullTestCompile(
                        projectDirectory,
                        config,
                        jdkStatus,
                        sources,
                        testCompileClasspath,
                        groovyCompilerLauncherClasspath,
                        groovyCompileClasspath,
                        kotlinCompilerLauncherClasspath,
                        kotlinOptions,
                        outputDirectory,
                        generatedSourcesDirectory,
                        classpaths,
                        options,
                        plan.fallbackReason(),
                        plan.fullDiagnostics(sources.allTestSources().size()),
                        plan.captureProcessorAttribution())
                .withPlatformApiWarning(platformApiWarning);
    }

    private TestCompileAttempt incrementalCompile(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath groovyCompilerLauncherClasspath,
            Classpath groovyCompileClasspath,
            Classpath kotlinCompilerLauncherClasspath,
            KotlinCompilerRunner.Options kotlinOptions,
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
                    groovyCompilerLauncherClasspath,
                    groovyCompileClasspath,
                    kotlinCompilerLauncherClasspath,
                    kotlinOptions,
                    outputDirectory,
                    generatedSourcesDirectory,
                    classpaths,
                    options,
                    "incremental-javac-failed",
                    plan.fullDiagnostics(sources.allTestSources().size()),
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
                    groovyCompilerLauncherClasspath,
                    groovyCompileClasspath,
                    kotlinCompilerLauncherClasspath,
                    kotlinOptions,
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
                    groovyCompilerLauncherClasspath,
                    groovyCompileClasspath,
                    kotlinCompilerLauncherClasspath,
                    kotlinOptions,
                    outputDirectory,
                    generatedSourcesDirectory, classpaths, options, plan, "processor-unattributed-output");
        }
        JavacResult combined = new JavacResult(
                execution.primary().sourceCount() + waves.dependentSourceCount(),
                outputDirectory,
                IncrementalJavacExecution.combinedOutput(execution.primary().output(), waves.dependentOutput()));
        return new TestCompileAttempt(
                combined,
                new JavacResult(0, outputDirectory, ""),
                new JavacResult(0, outputDirectory, ""),
                "incremental",
                "",
                plan.diagnostics(combined.sourceCount(), waves.validation()),
                attribution,
                execution.compiledSources());
    }

    private TestCompileAttempt fullTestFallback(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath groovyCompilerLauncherClasspath,
            Classpath groovyCompileClasspath,
            Classpath kotlinCompilerLauncherClasspath,
            KotlinCompilerRunner.Options kotlinOptions,
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
                groovyCompilerLauncherClasspath,
                groovyCompileClasspath,
                kotlinCompilerLauncherClasspath,
                kotlinOptions,
                outputDirectory,
                generatedSourcesDirectory,
                classpaths,
                options,
                fallbackReason,
                plan.fullDiagnostics(sources.allTestSources().size()),
                plan.captureProcessorAttribution());
    }

    private TestCompileAttempt fullTestCompile(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            Classpath testCompileClasspath,
            Classpath groovyCompilerLauncherClasspath,
            Classpath groovyCompileClasspath,
            Classpath kotlinCompilerLauncherClasspath,
            KotlinCompilerRunner.Options kotlinOptions,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            ClasspathSet classpaths,
            JavacOptions options,
            String fallbackReason,
            CompileDiagnostics diagnostics,
            boolean captureAttribution) {
        CompileOutputCleaner.resetTest(
                projectDirectory, config, outputDirectory, generatedSourcesDirectory);
        if (!sources.kotlinTestSources().isEmpty()) {
            return kotlinTestCompileExecutor.compile(
                    jdkStatus,
                    sources,
                    testCompileClasspath,
                    kotlinCompilerLauncherClasspath,
                    kotlinOptions,
                    outputDirectory,
                    fallbackReason,
                    diagnostics);
        }
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
                groovyCompilerLauncherClasspath,
                groovyCompileClasspath,
                outputDirectory);
        return new TestCompileAttempt(
                javacResult,
                groovyResult,
                new JavacResult(0, outputDirectory, ""),
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

}
