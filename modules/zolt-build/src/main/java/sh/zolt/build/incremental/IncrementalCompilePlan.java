package sh.zolt.build.incremental;

import sh.zolt.build.CompileDiagnostics;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record IncrementalCompilePlan(
        boolean incremental,
        List<Path> sourcesToCompile,
        String fallbackReason,
        List<Path> outputsToDelete,
        int sourcesAdded,
        int sourcesChanged,
        int sourcesDeleted,
        List<IncrementalCompileState.SourceRecord> changedPreviousRecords,
        Map<Path, IncrementalCompileState.SourceRecord> previousSources,
        Map<Path, IncrementalCompileState.ClassRecord> previousClasses,
        Map<String, List<Path>> reverseDependencies,
        boolean captureProcessorAttribution) {
    public IncrementalCompilePlan withCaptureProcessorAttribution(boolean captureProcessorAttribution) {
        return new IncrementalCompilePlan(
                incremental,
                sourcesToCompile,
                fallbackReason,
                outputsToDelete,
                sourcesAdded,
                sourcesChanged,
                sourcesDeleted,
                changedPreviousRecords,
                previousSources,
                previousClasses,
                reverseDependencies,
                captureProcessorAttribution);
    }

    public static IncrementalCompilePlan full(String reason) {
        return full(reason, List.of());
    }

    public static IncrementalCompilePlan full(String reason, List<Path> outputsToDelete) {
        return full(reason, outputsToDelete, 0);
    }

    public static IncrementalCompilePlan full(String reason, List<Path> outputsToDelete, int sourcesDeleted) {
        return full(reason, outputsToDelete, 0, 0, sourcesDeleted);
    }

    public static IncrementalCompilePlan full(
            String reason,
            List<Path> outputsToDelete,
            int sourcesAdded,
            int sourcesChanged,
            int sourcesDeleted) {
        return new IncrementalCompilePlan(
                false,
                List.of(),
                reason,
                outputsToDelete.stream().map(path -> path.toAbsolutePath().normalize()).sorted().toList(),
                sourcesAdded,
                sourcesChanged,
                sourcesDeleted,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                false);
    }

    public static IncrementalCompilePlan incremental(
            List<Path> sourcesToCompile,
            int sourcesAdded,
            List<IncrementalCompileState.SourceRecord> changedPreviousRecords,
            List<IncrementalCompileState.SourceRecord> sourceRecords,
            List<IncrementalCompileState.ClassRecord> classRecords,
            Map<String, List<Path>> reverseDependencies) {
        Map<Path, IncrementalCompileState.SourceRecord> previousSources = new LinkedHashMap<>();
        for (IncrementalCompileState.SourceRecord sourceRecord : sourceRecords) {
            previousSources.put(sourceRecord.path(), sourceRecord);
        }
        Map<Path, IncrementalCompileState.ClassRecord> previousClasses = new LinkedHashMap<>();
        for (IncrementalCompileState.ClassRecord classRecord : classRecords) {
            previousClasses.put(classRecord.outputPath(), classRecord);
        }
        return new IncrementalCompilePlan(
                true,
                sourcesToCompile.stream().map(path -> path.toAbsolutePath().normalize()).sorted().toList(),
                "",
                List.of(),
                sourcesAdded,
                changedPreviousRecords.size(),
                0,
                List.copyOf(changedPreviousRecords),
                previousSources,
                previousClasses,
                reverseDependencies == null ? Map.of() : reverseDependencies,
                false);
    }

    public Optional<IncrementalCompileState.ClassRecord> previousClass(Path output) {
        Path normalized = output.toAbsolutePath().normalize();
        return Optional.ofNullable(previousClasses.get(normalized));
    }

    public boolean hasSource(Path source) {
        return previousSources.containsKey(source.toAbsolutePath().normalize());
    }

    public List<Path> previousClassOutputs(List<Path> sources) {
        return sources.stream()
                .map(source -> previousSources.get(source.toAbsolutePath().normalize()))
                .filter(java.util.Objects::nonNull)
                .flatMap(record -> record.classOutputs().stream())
                .distinct()
                .toList();
    }

    /**
     * The generated sources, generated classes, and generated resources attributed to the given
     * handwritten sources in the previous state. On the isolating fast path these must be deleted before
     * recompiling the dirty sources so a removed annotation cannot leave a stale generated output behind.
     */
    public List<Path> previousGeneratedOutputs(List<Path> sources) {
        return sources.stream()
                .map(source -> previousSources.get(source.toAbsolutePath().normalize()))
                .filter(java.util.Objects::nonNull)
                .flatMap(record -> java.util.stream.Stream.of(
                                record.generatedSources(),
                                record.generatedClasses(),
                                record.generatedResources())
                        .flatMap(List::stream))
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    public CompileDiagnostics diagnostics(int sourcesRecompiled, IncrementalCompileValidation validation) {
        return new CompileDiagnostics(
                sourcesAdded,
                sourcesChanged,
                sourcesDeleted,
                sourcesRecompiled,
                validation.additionalSources().size(),
                outputsToDelete.size(),
                validation.abiChangedClasses(),
                validation.packagePrivateAbiChangedClasses());
    }

    public CompileDiagnostics fullDiagnostics(int sourcesRecompiled) {
        return new CompileDiagnostics(
                sourcesAdded,
                sourcesChanged,
                sourcesDeleted,
                sourcesRecompiled,
                0,
                outputsToDelete.size(),
                0,
                0);
    }
}
