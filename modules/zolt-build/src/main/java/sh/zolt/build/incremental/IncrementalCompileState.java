package sh.zolt.build.incremental;

import sh.zolt.build.BuildException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record IncrementalCompileState(
        String scope,
        Path projectDirectory,
        Path outputDirectory,
        Path generatedSourcesDirectory,
        String compilerSettingsHash,
        String buildFingerprintSha256,
        List<String> fallbackReasons,
        List<String> sourceRoots,
        List<String> generatedSourceRoots,
        List<ClasspathEntry> compileClasspath,
        List<ClasspathEntry> processorClasspath,
        List<SourceRecord> sources,
        List<ClassRecord> classes,
        Map<String, List<Path>> reverseDependencies,
        boolean processorAttributionComplete) {
    static final String MAIN_FILE_NAME = ".zolt-incremental-main.state";
    static final String TEST_FILE_NAME = ".zolt-incremental-test.state";

    public IncrementalCompileState {
        scope = requireText(scope, "Incremental compile state scope is required.");
        projectDirectory = normalize(projectDirectory, "Incremental compile state project directory is required.");
        outputDirectory = normalize(outputDirectory, "Incremental compile state output directory is required.");
        generatedSourcesDirectory = normalize(
                generatedSourcesDirectory,
                "Incremental compile state generated source directory is required.");
        compilerSettingsHash = requireText(
                compilerSettingsHash,
                "Incremental compile state compiler settings hash is required.");
        buildFingerprintSha256 = requireText(
                buildFingerprintSha256,
                "Incremental compile state build fingerprint hash is required.");
        fallbackReasons = sortedStrings(fallbackReasons);
        sourceRoots = sortedStrings(sourceRoots);
        generatedSourceRoots = sortedStrings(generatedSourceRoots);
        compileClasspath = sortedEntries(compileClasspath);
        processorClasspath = sortedEntries(processorClasspath);
        sources = sortedSources(sources);
        classes = sortedClasses(classes);
        reverseDependencies = sortedReverseDependencies(reverseDependencies);
    }

    public static Path mainStatePath(Path outputDirectory) {
        return outputDirectory.resolve(MAIN_FILE_NAME);
    }

    public static Path testStatePath(Path outputDirectory) {
        return outputDirectory.resolve(TEST_FILE_NAME);
    }

    public record ClasspathEntry(
            Path path,
            long size,
            long lastModifiedNanos,
            String hash) {
        public ClasspathEntry {
            path = normalize(path, "Incremental compile classpath entry path is required.");
            if (size < -1L || lastModifiedNanos < -1L) {
                throw new BuildException("Incremental compile classpath file metadata is invalid.");
            }
            hash = requireText(hash, "Incremental compile classpath entry hash is required.");
        }

        boolean hasRegularFileMetadata() {
            return size >= 0L && lastModifiedNanos >= 0L;
        }
    }

    public record SourceRecord(
            Path path,
            Path sourceRoot,
            Optional<String> generatedSourceStepId,
            String contentHash,
            String packageName,
            List<String> declaredTypes,
            List<Path> classOutputs,
            List<String> referencedClasses,
            List<Path> generatedSources,
            List<Path> generatedClasses,
            List<Path> generatedResources) {
        public SourceRecord(
                Path path,
                Path sourceRoot,
                Optional<String> generatedSourceStepId,
                String contentHash,
                String packageName,
                List<String> declaredTypes,
                List<Path> classOutputs,
                List<String> referencedClasses) {
            this(
                    path,
                    sourceRoot,
                    generatedSourceStepId,
                    contentHash,
                    packageName,
                    declaredTypes,
                    classOutputs,
                    referencedClasses,
                    List.of(),
                    List.of(),
                    List.of());
        }

        public SourceRecord {
            path = normalize(path, "Incremental compile source path is required.");
            sourceRoot = normalize(sourceRoot, "Incremental compile source root is required.");
            generatedSourceStepId = generatedSourceStepId == null ? Optional.empty() : generatedSourceStepId;
            generatedSourceStepId.ifPresent(id -> requireText(id, "Generated source step id cannot be blank."));
            contentHash = requireText(contentHash, "Incremental compile source hash is required.");
            packageName = packageName == null ? "" : packageName;
            declaredTypes = sortedStrings(declaredTypes);
            classOutputs = sortedPaths(classOutputs);
            referencedClasses = sortedStrings(referencedClasses);
            generatedSources = sortedPaths(generatedSources);
            generatedClasses = sortedPaths(generatedClasses);
            generatedResources = sortedPaths(generatedResources);
        }
    }

    public record ClassRecord(
            String binaryName,
            Path outputPath,
            String classFileHash,
            String abiHash,
            String packagePrivateAbiHash,
            int accessFlags,
            Optional<String> superName,
            List<String> interfaces) {
        public ClassRecord {
            binaryName = requireText(binaryName, "Incremental compile class binary name is required.");
            outputPath = normalize(outputPath, "Incremental compile class output path is required.");
            classFileHash = requireText(classFileHash, "Incremental compile class file hash is required.");
            abiHash = requireText(abiHash, "Incremental compile class ABI hash is required.");
            packagePrivateAbiHash = requireText(
                    packagePrivateAbiHash,
                    "Incremental compile package-private ABI hash is required.");
            superName = superName == null ? Optional.empty() : superName;
            superName.ifPresent(name -> requireText(name, "Incremental compile class super name cannot be blank."));
            interfaces = sortedStrings(interfaces);
        }
    }

    private static Map<String, List<Path>> sortedReverseDependencies(Map<String, List<Path>> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Path>> sorted = new LinkedHashMap<>();
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(
                        requireText(entry.getKey(), "Reverse dependency class name cannot be blank."),
                        sortedPaths(entry.getValue())));
        return Collections.unmodifiableMap(sorted);
    }

    private static List<ClasspathEntry> sortedEntries(List<ClasspathEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .sorted(Comparator.comparing(entry -> entry.path().toString()))
                .toList();
    }

    private static List<SourceRecord> sortedSources(List<SourceRecord> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .sorted(Comparator.comparing(source -> source.path().toString()))
                .toList();
    }

    private static List<ClassRecord> sortedClasses(List<ClassRecord> classes) {
        if (classes == null || classes.isEmpty()) {
            return List.of();
        }
        return classes.stream()
                .sorted(Comparator.comparing(ClassRecord::binaryName))
                .toList();
    }

    private static List<Path> sortedPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        return paths.stream()
                .map(path -> normalize(path, "Incremental compile path list contains a null path."))
                .sorted()
                .toList();
    }

    private static List<String> sortedStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> requireText(value, "Incremental compile string list contains a blank value."))
                .sorted()
                .toList();
    }

    private static Path normalize(Path path, String message) {
        if (path == null) {
            throw new BuildException(message);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BuildException(message);
        }
        return value;
    }
}
