package sh.zolt.build.incremental;

import static sh.zolt.build.incremental.IncrementalCompileInputHasher.hash;
import static sh.zolt.build.incremental.IncrementalCompileInputHasher.hashText;
import static sh.zolt.build.incremental.IncrementalCompileInputHasher.relative;

import sh.zolt.build.BuildException;
import sh.zolt.build.abi.ClassFileAbi;
import sh.zolt.build.abi.ClassFileAbiReader;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class IncrementalCompileStateRecorder {
    private static final String MAIN_FINGERPRINT_FILE = ".zolt-build-main.fingerprint";
    private static final String TEST_FINGERPRINT_FILE = ".zolt-build-test.fingerprint";

    private final IncrementalCompileStateCodec codec;
    private final ClassFileAbiReader abiReader;
    private final IncrementalCompileSourceRecordBuilder sourceRecordBuilder;
    private final IncrementalAnnotationProcessorClassifier processorClassifier =
            new IncrementalAnnotationProcessorClassifier();
    private final IncrementalGeneratedOutputAttributor attributor = new IncrementalGeneratedOutputAttributor();

    public IncrementalCompileStateRecorder() {
        this(
                new IncrementalCompileStateCodec(),
                new ClassFileAbiReader(),
                new IncrementalCompileSourceRecordBuilder());
    }

    IncrementalCompileStateRecorder(
            IncrementalCompileStateCodec codec,
            ClassFileAbiReader abiReader) {
        this(codec, abiReader, new IncrementalCompileSourceRecordBuilder());
    }

    IncrementalCompileStateRecorder(
            IncrementalCompileStateCodec codec,
            ClassFileAbiReader abiReader,
            IncrementalCompileSourceRecordBuilder sourceRecordBuilder) {
        this.codec = codec;
        this.abiReader = abiReader;
        this.sourceRecordBuilder = sourceRecordBuilder;
    }

    public void deleteMainState(Path outputDirectory) {
        deleteState(IncrementalCompileState.mainStatePath(outputDirectory));
    }

    public void deleteTestState(Path outputDirectory) {
        deleteState(IncrementalCompileState.testStatePath(outputDirectory));
    }

    public void recordMain(
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        recordMain(
                projectDirectory,
                config,
                sources,
                classpaths,
                outputDirectory,
                generatedSourcesDirectory,
                GeneratedOutputAttribution.absent(),
                List.of());
    }

    public void recordMain(
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            GeneratedOutputAttribution attribution,
            List<Path> compiledSources) {
        record(
                "main",
                projectDirectory,
                config,
                sources.mainSources(),
                config.build().sourceRoots(),
                config.build().generatedMainSources(),
                classpaths.compile(),
                classpaths.processor(),
                outputDirectory,
                generatedSourcesDirectory,
                IncrementalCompileState.mainStatePath(outputDirectory),
                outputDirectory.resolve(MAIN_FINGERPRINT_FILE),
                processorFallbackReasons(classpaths.processor()),
                attribution,
                compiledSources);
    }

    private List<String> processorFallbackReasons(Classpath processorClasspath) {
        String reason = processorClassifier.fallbackReason(processorClasspath);
        return reason.isEmpty() ? List.of() : List.of(reason);
    }

    public void recordTest(
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            GeneratedOutputAttribution attribution,
            List<Path> compiledSources) {
        List<String> fallbackReasons = new ArrayList<>(processorFallbackReasons(processorClasspath));
        if (!sources.groovyTestSources().isEmpty()) {
            fallbackReasons.add("groovy-test-sources");
        }
        record(
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
                outputDirectory.resolve(TEST_FINGERPRINT_FILE),
                fallbackReasons,
                attribution,
                compiledSources);
    }

    private void record(
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
            Path fingerprintPath,
            List<String> fallbackReasons,
            GeneratedOutputAttribution attribution,
            List<Path> compiledSources) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Optional<IncrementalCompileState> previousState = codec.read(statePath);
        List<String> stateFallbackReasons = new ArrayList<>(fallbackReasons);
        List<Path> sourceRoots = sourceRoots(projectRoot, configuredSourceRoots, generatedSteps);
        Map<Path, String> generatedStepIds = generatedStepIds(projectRoot, generatedSteps);
        List<ClassFileAbi> classFiles = classFiles(outputDirectory, stateFallbackReasons);
        List<IncrementalCompileState.ClassRecord> classRecords = classRecords(classFiles);
        List<IncrementalCompileState.SourceRecord> baseRecords = sourceRecordBuilder.sourceRecords(
                projectRoot,
                sources,
                sourceRoots,
                generatedStepIds,
                classFiles,
                IncrementalCompileInputHasher::hash);
        AttributionOutcome outcome = attributionOutcome(
                processorClasspath, baseRecords, classRecords, attribution, compiledSources, previousState);
        stateFallbackReasons.addAll(outcome.fallbackReasons());
        codec.write(
                statePath,
                new IncrementalCompileState(
                        scope,
                        projectRoot,
                        outputDirectory,
                        generatedSourcesDirectory,
                        hashText(config.compilerSettings().toString()),
                        hash(fingerprintPath),
                        stateFallbackReasons,
                        sourceRoots.stream().map(path -> relative(projectRoot, path)).toList(),
                        generatedSteps.stream().map(GeneratedSourceStep::output).sorted().toList(),
                        classpathEntries(
                                compileClasspath,
                                previousState.map(IncrementalCompileState::compileClasspath).orElse(List.of())),
                        classpathEntries(
                                processorClasspath,
                                previousState.map(IncrementalCompileState::processorClasspath).orElse(List.of())),
                        outcome.sources(),
                        classRecords,
                        reverseDependencies(outcome.sources()),
                        outcome.attributionComplete()));
    }

    private AttributionOutcome attributionOutcome(
            Classpath processorClasspath,
            List<IncrementalCompileState.SourceRecord> baseRecords,
            List<IncrementalCompileState.ClassRecord> classRecords,
            GeneratedOutputAttribution attribution,
            List<Path> compiledSources,
            Optional<IncrementalCompileState> previousState) {
        if (!processorClassifier.isolating(processorClasspath)) {
            return new AttributionOutcome(baseRecords, false, List.of());
        }
        IncrementalGeneratedOutputAttributor.Result result = attributor.apply(
                baseRecords, classRecords, attribution, compiledSources, previousSources(previousState));
        if (!attribution.present()) {
            return new AttributionOutcome(result.sources(), false, List.of("processor-generated-outputs-untracked"));
        }
        if (attribution.unattributed() || !result.complete()) {
            return new AttributionOutcome(result.sources(), false, List.of("processor-unattributed-output"));
        }
        return new AttributionOutcome(result.sources(), true, List.of());
    }

    private static Map<Path, IncrementalCompileState.SourceRecord> previousSources(
            Optional<IncrementalCompileState> previousState) {
        return previousState
                .map(state -> {
                    Map<Path, IncrementalCompileState.SourceRecord> previous = new LinkedHashMap<>();
                    state.sources().forEach(source -> previous.put(source.path(), source));
                    return previous;
                })
                .orElse(Map.of());
    }

    private record AttributionOutcome(
            List<IncrementalCompileState.SourceRecord> sources,
            boolean attributionComplete,
            List<String> fallbackReasons) {
    }

    private List<ClassFileAbi> classFiles(Path outputDirectory, List<String> fallbackReasons) {
        if (!Files.isDirectory(outputDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(outputDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .flatMap(path -> readAbi(path, fallbackReasons).stream())
                    .toList();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not inspect compiled classes under "
                            + outputDirectory
                            + ". Check that the directory is readable.",
                    exception);
        }
    }

    private Optional<ClassFileAbi> readAbi(Path classFile, List<String> fallbackReasons) {
        try {
            return Optional.of(abiReader.read(classFile));
        } catch (BuildException exception) {
            if (!fallbackReasons.contains("unreadable-class-output")) {
                fallbackReasons.add("unreadable-class-output");
            }
            return Optional.empty();
        }
    }

    private static List<IncrementalCompileState.ClassRecord> classRecords(List<ClassFileAbi> classFiles) {
        return classFiles.stream()
                .map(abi -> new IncrementalCompileState.ClassRecord(
                        abi.binaryName(),
                        abi.classFile(),
                        hash(abi.classFile()),
                        abi.abiHash(),
                        abi.packagePrivateAbiHash(),
                        abi.accessFlags(),
                        abi.superName(),
                        abi.interfaces()))
                .toList();
    }

    private static Map<String, List<Path>> reverseDependencies(List<IncrementalCompileState.SourceRecord> sourceRecords) {
        Map<String, List<Path>> reverseDependencies = new LinkedHashMap<>();
        for (IncrementalCompileState.SourceRecord source : sourceRecords) {
            for (String referencedClass : source.referencedClasses()) {
                reverseDependencies
                        .computeIfAbsent(referencedClass, ignored -> new ArrayList<>())
                        .add(source.path());
            }
        }
        return reverseDependencies;
    }

    private static List<IncrementalCompileState.ClasspathEntry> classpathEntries(
            Classpath classpath,
            List<IncrementalCompileState.ClasspathEntry> previousEntries) {
        Map<Path, IncrementalCompileState.ClasspathEntry> previous = new LinkedHashMap<>();
        previousEntries.forEach(entry -> previous.put(entry.path(), entry));
        return classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .map(path -> IncrementalCompileInputHasher.classpathEntry(path, previous.get(path)))
                .toList();
    }

    private static List<Path> sourceRoots(
            Path projectRoot,
            List<String> configuredSourceRoots,
            List<GeneratedSourceStep> generatedSteps) {
        List<Path> roots = new ArrayList<>();
        configuredSourceRoots.stream()
                .map(root -> projectRoot.resolve(root).normalize())
                .forEach(roots::add);
        generatedSteps.stream()
                .map(step -> projectRoot.resolve(step.output()).normalize())
                .forEach(roots::add);
        return roots.stream().distinct().sorted().toList();
    }

    private static Map<Path, String> generatedStepIds(Path projectRoot, List<GeneratedSourceStep> generatedSteps) {
        Map<Path, String> ids = new LinkedHashMap<>();
        generatedSteps.stream()
                .sorted(Comparator.comparing(GeneratedSourceStep::id))
                .forEach(step -> ids.put(projectRoot.resolve(step.output()).normalize(), step.id()));
        return ids;
    }

    private static void deleteState(Path statePath) {
        try {
            Files.deleteIfExists(statePath);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not delete stale incremental compile state at "
                            + statePath
                            + ". Check that the build output directory is writable.",
                    exception);
        }
    }
}
