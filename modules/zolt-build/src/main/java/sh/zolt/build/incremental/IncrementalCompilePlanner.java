package sh.zolt.build.incremental;

import static sh.zolt.build.incremental.IncrementalCompileInputHasher.hash;

import sh.zolt.build.abi.ClassFileAbiReader;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class IncrementalCompilePlanner {
    private final IncrementalCompileStateCodec codec;
    private final IncrementalCompileStateValidator stateValidator;
    private final IncrementalCompileAbiValidator abiValidator;
    private final IncrementalAnnotationProcessorClassifier processorClassifier;

    public IncrementalCompilePlanner() {
        this(new IncrementalCompileStateCodec(), new ClassFileAbiReader());
    }

    IncrementalCompilePlanner(
            IncrementalCompileStateCodec codec,
            ClassFileAbiReader abiReader) {
        this.codec = codec;
        this.stateValidator = new IncrementalCompileStateValidator();
        this.abiValidator = new IncrementalCompileAbiValidator(abiReader);
        this.processorClassifier = new IncrementalAnnotationProcessorClassifier();
    }

    public IncrementalCompilePlan planMain(
            Path projectDirectory,
            ProjectConfig config,
            List<Path> sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        return planMain(
                projectDirectory,
                config,
                sources,
                compileClasspath,
                processorClasspath,
                outputDirectory,
                generatedSourcesDirectory,
                "non-source-input-changed",
                null);
    }

    public IncrementalCompilePlan planMain(
            Path projectDirectory,
            ProjectConfig config,
            List<Path> sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            String noSourceFallbackReason,
            JdkStatus jdkStatus) {
        return plan(
                "main",
                projectDirectory,
                config,
                sources,
                config.build().sourceRoots(),
                config.build().generatedMainSources(),
                compileClasspath,
                processorClasspath,
                outputDirectory,
                generatedSourcesDirectory,
                IncrementalCompileState.mainStatePath(outputDirectory),
                List.of(),
                noSourceFallbackReason,
                compilerIdentity(jdkStatus));
    }

    public IncrementalCompilePlan planTest(
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus) {
        List<String> fallbackReasons = new ArrayList<>();
        if (!sources.groovyTestSources().isEmpty()) {
            fallbackReasons.add("groovy-test-sources");
        }
        return plan(
                "test",
                projectDirectory,
                config,
                sources.testSources(),
                config.build().testSources(),
                config.build().generatedTestSources(),
                compileClasspath,
                processorClasspath,
                outputDirectory,
                generatedSourcesDirectory,
                IncrementalCompileState.testStatePath(outputDirectory),
                fallbackReasons,
                "non-source-input-changed",
                compilerIdentity(jdkStatus));
    }

    private IncrementalCompilePlan plan(
            String scope,
            Path projectDirectory,
            ProjectConfig config,
            List<Path> sources,
            List<String> configuredSourceRoots,
            List<GeneratedSourceStep> generatedSteps,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            Path statePath,
            List<String> additionalFallbackReasons,
            String noSourceFallbackReason,
            String compilerIdentity) {
        return planResolved(
                scope,
                projectDirectory,
                config,
                sources,
                configuredSourceRoots,
                generatedSteps,
                compileClasspath,
                processorClasspath,
                outputDirectory,
                generatedSourcesDirectory,
                statePath,
                additionalFallbackReasons,
                noSourceFallbackReason,
                compilerIdentity)
                .withCaptureProcessorAttribution(processorClassifier.isolating(processorClasspath));
    }

    private IncrementalCompilePlan planResolved(
            String scope,
            Path projectDirectory,
            ProjectConfig config,
            List<Path> sources,
            List<String> configuredSourceRoots,
            List<GeneratedSourceStep> generatedSteps,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            Path statePath,
            List<String> additionalFallbackReasons,
            String noSourceFallbackReason,
            String compilerIdentity) {
        String processorFallback = processorClassifier.fallbackReason(processorClasspath);
        if (!processorFallback.isEmpty()) {
            return IncrementalCompilePlan.full(processorFallback);
        }
        if (!additionalFallbackReasons.isEmpty()) {
            return IncrementalCompilePlan.full(additionalFallbackReasons.getFirst());
        }
        Optional<IncrementalCompileState> optionalState = codec.read(statePath);
        if (optionalState.isEmpty()) {
            return IncrementalCompilePlan.full("missing-state");
        }
        IncrementalCompileState state = optionalState.orElseThrow();
        if (!state.fallbackReasons().isEmpty()) {
            return IncrementalCompilePlan.full(state.fallbackReasons().getFirst());
        }
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        String validationFailure = stateValidator.validate(
                state,
                scope,
                projectRoot,
                config,
                compilerIdentity,
                configuredSourceRoots,
                generatedSteps,
                compileClasspath,
                outputDirectory,
                generatedSourcesDirectory);
        if (!validationFailure.isBlank()) {
            return IncrementalCompilePlan.full(validationFailure);
        }
        if (!processorClasspath.entries().isEmpty() && !state.processorAttributionComplete()) {
            return IncrementalCompilePlan.full("processor-generated-outputs-untracked");
        }

        Map<Path, IncrementalCompileState.SourceRecord> previousSources = new LinkedHashMap<>();
        for (IncrementalCompileState.SourceRecord source : state.sources()) {
            previousSources.put(source.path(), source);
        }
        Map<Path, String> currentHashes = currentSourceHashes(sources);
        Set<Path> deletedSources = new LinkedHashSet<>(previousSources.keySet());
        deletedSources.removeAll(currentHashes.keySet());
        if (!deletedSources.isEmpty()) {
            return IncrementalCompilePlan.full(
                    "source-deleted",
                    outputsForDeletedSources(deletedSources, previousSources),
                    deletedSources.size());
        }

        List<Path> dirtySources = new ArrayList<>();
        List<IncrementalCompileState.SourceRecord> changedPreviousRecords = new ArrayList<>();
        int addedSourceCount = 0;
        for (Map.Entry<Path, String> entry : currentHashes.entrySet()) {
            IncrementalCompileState.SourceRecord previous = previousSources.get(entry.getKey());
            if (previous == null) {
                dirtySources.add(entry.getKey());
                addedSourceCount++;
                continue;
            }
            if (!previous.contentHash().equals(entry.getValue())) {
                if (previous.classOutputs().isEmpty() && !previous.declaredTypes().isEmpty()) {
                    return IncrementalCompilePlan.full("unmapped-source-classes");
                }
                dirtySources.add(entry.getKey());
                changedPreviousRecords.add(previous);
            }
        }
        if (dirtySources.isEmpty()) {
            return IncrementalCompilePlan.full(normalizeNoSourceFallbackReason(noSourceFallbackReason));
        }
        return IncrementalCompilePlan.incremental(
                dirtySources,
                addedSourceCount,
                changedPreviousRecords,
                state.sources(),
                state.classes(),
                state.reverseDependencies());
    }

    private static String normalizeNoSourceFallbackReason(String reason) {
        return reason == null || reason.isBlank() ? "non-source-input-changed" : reason;
    }

    private static String compilerIdentity(JdkStatus jdkStatus) {
        return jdkStatus == null ? "unspecified" : jdkStatus.effectiveCompilerIdentity();
    }

    public IncrementalCompileWaveResult validateAndCompileDependents(
            IncrementalCompilePlan plan,
            IncrementalDependentCompiler compiler) {
        return abiValidator.validateWaves(plan, compiler);
    }

    private static List<Path> outputsForDeletedSources(
            Set<Path> deletedSources,
            Map<Path, IncrementalCompileState.SourceRecord> previousSources) {
        return deletedSources.stream()
                .flatMap(source -> previousSources.get(source).classOutputs().stream())
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .sorted()
                .toList();
    }

    private static Map<Path, String> currentSourceHashes(List<Path> sources) {
        Map<Path, String> hashes = new LinkedHashMap<>();
        sources.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .forEach(path -> hashes.put(path, hash(path)));
        return hashes;
    }

}
