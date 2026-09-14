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
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;
    private final IncrementalCompilePlanner incrementalCompilePlanner;
    private final IncrementalJavacExecution incrementalJavacExecution;

    public MainCompileSourceExecutor(
            JavacRunner javacRunner,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder,
            IncrementalCompilePlanner incrementalCompilePlanner) {
        this.javacRunner = javacRunner;
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
        if (compileSkipped) {
            return new Attempt(
                    new JavacResult(sources.mainSources().size(), outputDirectory, ""),
                    "skipped",
                    "",
                    CompileDiagnostics.empty());
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
                fingerprintMissReason,
                jdkStatus);
        if (plan.incremental()) {
            return withPlatformApiWarning(
                    incrementalCompile(
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
        IncrementalJavacExecution.deleteOutputs(plan.outputsToDelete());
        return withPlatformApiWarning(
                fullCompile(
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
                    jdkStatus, sources, classpaths, outputDirectory, generatedSourcesDirectory,
                    options, plan, waves.validation().fallbackReason());
        }
        if (attribution.present() && attribution.unattributed()) {
            return fullFallback(
                    jdkStatus, sources, classpaths, outputDirectory, generatedSourcesDirectory,
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
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JavacOptions options,
            IncrementalCompilePlan plan,
            String fallbackReason) {
        incrementalCompileStateRecorder.deleteMainState(outputDirectory);
        IncrementalJavacExecution.deleteOutputs(plan.outputsToDelete());
        return fullCompile(
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
            JdkStatus jdkStatus,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JavacOptions options,
            String fallbackReason,
            CompileDiagnostics diagnostics,
            boolean captureAttribution) {
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
