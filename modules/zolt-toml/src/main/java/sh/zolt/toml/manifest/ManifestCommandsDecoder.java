package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.toml.schema.FinalManifestCommandFields;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Decodes authored task collections without applying execution context. */
final class ManifestCommandsDecoder {
    Optional<Map<LocalId, AuthoredTask>> decodeTasks(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.SectionEntry> entries =
                index.sectionEntries(FinalManifestPaths.TASK);
        if (index.section(FinalManifestPaths.TASKS).isEmpty() && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<LocalId, AuthoredTask> tasks = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry : entries) {
            LocalId id = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new LocalId(entry.key()));
            AuthoredTask task = decodeTask(index, entry);
            if (tasks.put(id, task) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate task `" + id + "`.");
            }
        }
        return Optional.of(ManifestModelValues.immutableSortedMap(
                tasks,
                LocalId::compareTo,
                "Task ID",
                "Task"));
    }

    private static AuthoredTask decodeTask(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        Optional<String> description = index
                .field(entry, FinalManifestCommandFields.TASK_DESCRIPTION)
                .map(ManifestCommandsDecoder::description);
        ValidatedManifestField runField = ManifestSemanticDiagnostics.requiredField(
                index, entry, FinalManifestCommandFields.TASK_RUN);
        AuthoredTask task = run(runField, description);

        Optional<ValidatedManifestField> cwdField =
                index.field(entry, FinalManifestCommandFields.TASK_CWD);
        if (cwdField.isPresent()) {
            task = cwd(cwdField.orElseThrow(), description, task.run());
        }

        Optional<ValidatedManifestField> envField =
                index.field(entry, FinalManifestCommandFields.TASK_ENV);
        if (envField.isPresent()) {
            task = environment(
                    envField.orElseThrow(), description, task.run(), task.cwd());
        }
        return task;
    }

    private static String description(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        return ManifestSemanticDiagnostics.construct(field, () -> {
            ManifestModelValues.requireNonBlank(value, "Task description");
            return value;
        });
    }

    private static AuthoredTask run(
            ValidatedManifestField field,
            Optional<String> description) {
        List<String> raw = ManifestTomlValues.strings(field);
        if (raw.isEmpty()) {
            return ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredTask(
                            description, List.of(), Optional.empty(), Map.of()));
        }

        ArrayList<String> values = new ArrayList<>(raw.size());
        AuthoredTask task = null;
        for (int index = 0; index < raw.size(); index++) {
            values.add(raw.get(index));
            List<String> prefix = List.copyOf(values);
            task = ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new AuthoredTask(
                            description, prefix, Optional.empty(), Map.of()));
        }
        return Objects.requireNonNull(task, "Decoded task run must not be null.");
    }

    private static AuthoredTask cwd(
            ValidatedManifestField field,
            Optional<String> description,
            List<String> run) {
        String raw = ManifestTomlValues.string(field);
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredTask(
                        description,
                        run,
                        Optional.of(new ManifestRelativePath(raw)),
                        Map.of()));
    }

    private static AuthoredTask environment(
            ValidatedManifestField field,
            Optional<String> description,
            List<String> run,
            Optional<ManifestRelativePath> cwd) {
        Map<String, String> raw = ManifestSemanticDiagnostics.construct(
                field, () -> ManifestTomlValues.stringMap(field));
        LinkedHashMap<EnvironmentVariableName, String> values = new LinkedHashMap<>();
        AuthoredTask task = ManifestSemanticDiagnostics.construct(
                field, () -> new AuthoredTask(description, run, cwd, Map.of()));
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            EnvironmentVariableName name = ManifestSemanticDiagnostics.construct(
                    field, () -> keyed(key, () -> new EnvironmentVariableName(key)));
            values.put(name, entry.getValue());
            task = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> keyed(
                            key,
                            () -> new AuthoredTask(description, run, cwd, values)));
        }
        return task;
    }

    private static <T> T keyed(String key, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Environment entry `" + key + "`: " + failure.getMessage(),
                    failure);
        }
    }
}
