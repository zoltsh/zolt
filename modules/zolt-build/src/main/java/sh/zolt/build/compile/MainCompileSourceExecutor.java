package sh.zolt.build.compile;

import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.JavacException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.incremental.GeneratedOutputAttribution;
import sh.zolt.build.incremental.IncrementalCompilePlan;
import sh.zolt.build.incremental.IncrementalCompilePlanner;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.build.incremental.IncrementalCompileWaveResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.List;

public final class MainCompileSourceExecutor {
    private final JavacRunner javacRunner;
    private final GroovyCompilerRunner groovyCompilerRunner;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final IncrementalCompilePlanner incrementalCompilePlanner;
    private final IncrementalJavacExecution incrementalJavacExecution;

    public MainCompileSourceExecutor(
            JavacRunner javacRunner,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder,
            IncrementalCompilePlanner incrementalCompilePlanner) {
        this(
                javacRunner,
                new GroovyCompilerRunner(),
                incrementalCompileStateRecorder,
                incrementalCompilePlanner);
    }

    public MainCompileSourceExecutor(
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

    public Attempt compile(
            boolean compileSkipped,
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus) {
        return compile(
                compileSkipped,
                "non-source-input-changed",
                projectDirectory,
                config,
                sources,
                classpaths,
                outputDirectory,
                generatedSourcesDirectory,
                jdkStatus);
    }

    public Attempt compile(
            boolean compileSkipped,
            String fingerprintMissReason,
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus) {
        GroovyCompilerRunner.JointOptions groovyOptions = groovyOptions(
                config, sources, classpaths, jdkStatus);
        if (compileSkipped) {
            return new Attempt(
                    new JavacResult(sources.allMainSources().size(), outputDirectory, ""),
                    "skipped",
                    "",
                    CompileDiagnostics.empty());
        }
        if (groovyOptions != null) {
            return jointGroovyCompile(
                    projectDirectory,
                    config,
                    sources,
                    classpaths,
                    outputDirectory,
                    generatedSourcesDirectory,
                    jdkStatus,
                    groovyOptions);
        }
        boolean hostMode = config.compilerSettings().mainHostPlatformApi()
                && !MainCompileOptions.effectiveRelease(config).isBlank();
        CompilerPlatformApi.rejectModularHost(hostMode, sources.mainSources(), "main");
        JavacOptions options = MainCompileOptions.forMainSources(
                config, sources.mainSources(), classpaths.compile(), hostMode);
        String platformApiWarning = CompilerPlatformApi.determinismWarning(hostMode, "main", jdkStatus);
        IncrementalCompilePlan plan = incrementalCompilePlanner.planMain(
                projectDirectory,
                config,
                sources.mainSources(),
                classpaths.compile(),
                classpaths.processor(),
                outputDirectory,
                generatedSourcesDirectory,
                EffectiveCompilerIdentity.of(jdkStatus),
                fingerprintMissReason);
        if (plan.incremental()) {
            return withPlatformApiWarning(
                    incrementalCompile(
                            projectDirectory,
                            config,
                            jdkStatus,
                            sources,
                            classpaths,
                            outputDirectory,
                            generatedSourcesDirectory,
                            options,
                            plan),
                    platformApiWarning);
        }
        incrementalCompileStateRecorder.deleteMainState(outputDirectory);
        return withPlatformApiWarning(
                fullCompile(
                        projectDirectory,
                        config,
                        jdkStatus,
                        sources,
                        classpaths,
                        outputDirectory,
                        generatedSourcesDirectory,
                        options,
                        plan.fallbackReason(),
                        plan.fullDiagnostics(sources.mainSources().size()),
                        plan.captureProcessorAttribution()),
                platformApiWarning);
    }

    /** Validates Groovy main compilation before any cache restore or output cleanup can mutate state. */
    public void preflight(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus) {
        groovyOptions(config, sources, classpaths, jdkStatus);
    }

    private static GroovyCompilerRunner.JointOptions groovyOptions(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus) {
        if (sources.groovyMainSources().isEmpty()) {
            return null;
        }
        return GroovyJointCompilePolicy.options(
                config, sources.allMainSources(), classpaths, jdkStatus);
    }

    private Attempt jointGroovyCompile(
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus,
            GroovyCompilerRunner.JointOptions options) {
        List<Path> allSources = sources.allMainSources();
        String platformApiWarning = CompilerPlatformApi.determinismWarning(
                options.hostPlatformApi(), "main", jdkStatus);
        incrementalCompileStateRecorder.deleteMainState(outputDirectory);
        CompileOutputCleaner.resetMain(
                projectDirectory, config, outputDirectory, generatedSourcesDirectory);
        JavacResult result = groovyCompilerRunner.compileJoint(
                jdkStatus.java().orElseThrow(),
                allSources,
                classpaths.compile(),
                outputDirectory,
                options);
        return withPlatformApiWarning(
                new Attempt(
                        result,
                        "full",
                        "groovy-main-sources",
                        CompileDiagnostics.legacy(allSources.size(), false),
                        GeneratedOutputAttribution.absent(),
                        allSources),
                platformApiWarning);
    }

    private static Attempt withPlatformApiWarning(Attempt attempt, String warning) {
        if (warning == null || warning.isBlank()) {
            return attempt;
        }
        return new Attempt(
                new JavacResult(
                        attempt.result().sourceCount(),
                        attempt.result().outputDirectory(),
                        IncrementalJavacExecution.combinedOutput(warning, attempt.result().output())),
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
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JavacOptions options,
            IncrementalCompilePlan plan) {
        IncrementalJavacExecution.Result execution;
        try {
            execution = incrementalJavacExecution.run(
                    jdkStatus.javac().orElseThrow(),
                    plan,
                    classpaths.compile(),
                    outputDirectory,
                    classpaths.processor(),
                    generatedSourcesDirectory,
                    options);
        } catch (JavacException exception) {
            incrementalCompileStateRecorder.deleteMainState(outputDirectory);
            return fullCompile(
                    projectDirectory,
                    config,
                    jdkStatus,
                    sources,
                    classpaths,
                    outputDirectory,
                    generatedSourcesDirectory,
                    options,
                    "incremental-javac-failed",
                    plan.fullDiagnostics(sources.mainSources().size()),
                    plan.captureProcessorAttribution());
        }
        IncrementalCompileWaveResult waves = execution.waves();
        GeneratedOutputAttribution attribution = execution.attribution();
        if (waves.hasFallback()) {
            return fullFallback(
                    projectDirectory,
                    config,
                    jdkStatus,
                    sources,
                    classpaths,
                    outputDirectory,
                    generatedSourcesDirectory,
                    options, plan, waves.validation().fallbackReason());
        }
        if (attribution.present() && attribution.unattributed()) {
            return fullFallback(
                    projectDirectory,
                    config,
                    jdkStatus,
                    sources,
                    classpaths,
                    outputDirectory,
                    generatedSourcesDirectory,
                    options, plan, "processor-unattributed-output");
        }
        JavacResult combined = new JavacResult(
                execution.primary().sourceCount() + waves.dependentSourceCount(),
                outputDirectory,
                IncrementalJavacExecution.combinedOutput(execution.primary().output(), waves.dependentOutput()),
                attribution);
        return new Attempt(
                combined,
                "incremental",
                "",
                plan.diagnostics(combined.sourceCount(), waves.validation()),
                attribution,
                execution.compiledSources());
    }

    private Attempt fullFallback(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JavacOptions options,
            IncrementalCompilePlan plan,
            String fallbackReason) {
        incrementalCompileStateRecorder.deleteMainState(outputDirectory);
        return fullCompile(
                projectDirectory,
                config,
                jdkStatus,
                sources,
                classpaths,
                outputDirectory,
                generatedSourcesDirectory,
                options,
                fallbackReason,
                plan.fullDiagnostics(sources.mainSources().size()),
                plan.captureProcessorAttribution());
    }

    private Attempt fullCompile(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JavacOptions options,
            String fallbackReason,
            CompileDiagnostics diagnostics,
            boolean captureAttribution) {
        CompileOutputCleaner.resetMain(
                projectDirectory, config, outputDirectory, generatedSourcesDirectory);
        JavacResult result = javacRunner.compile(
                jdkStatus.javac().orElseThrow(),
                sources.mainSources(),
                classpaths.compile(),
                outputDirectory,
                classpaths.processor(),
                generatedSourcesDirectory,
                options,
                captureAttribution);
        return new Attempt(
                result,
                "full",
                fallbackReason,
                diagnostics,
                result.attribution(),
                sources.mainSources());
    }

    public record Attempt(
            JavacResult result,
            String mode,
            String fallbackReason,
            CompileDiagnostics diagnostics,
            GeneratedOutputAttribution attribution,
            List<Path> compiledSources) {
        public Attempt(
                JavacResult result,
                String mode,
                String fallbackReason,
                CompileDiagnostics diagnostics) {
            this(result, mode, fallbackReason, diagnostics, GeneratedOutputAttribution.absent(), List.of());
        }

        public int sourceCount() {
            return result.sourceCount();
        }

        public Path outputDirectory() {
            return result.outputDirectory();
        }

        public String output() {
            return result.output();
        }
    }
}
