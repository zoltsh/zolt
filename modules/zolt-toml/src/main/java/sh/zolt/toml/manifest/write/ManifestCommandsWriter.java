package sh.zolt.toml.manifest.write;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.toml.schema.FinalManifestCommandFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical authored tasks and built-in command aliases. */
final class ManifestCommandsWriter {
    private static final ManifestSection TASK = section(FinalManifestPaths.TASK);
    private static final ManifestSection ALIASES = section(FinalManifestPaths.ALIASES);

    void write(ManifestTomlEmitter emitter, AuthoredCommands commands) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        AuthoredCommands authored = Objects.requireNonNull(
                commands, "Authored commands are required.");
        sorted(authored.tasks()).forEach(entry -> writeTask(
                emitter, entry.getKey(), entry.getValue()));
        if (!authored.aliases().isEmpty()) {
            emitter.section(ALIASES);
            sorted(authored.aliases()).forEach(entry -> emitter.dynamicField(
                    FinalManifestCommandFields.ALIASES_ENTRY,
                    entry.getKey().value(),
                    strings(
                            FinalManifestCommandFields.ALIASES_ENTRY,
                            entry.getKey().value(),
                            entry.getValue().argv())));
        }
    }

    private static void writeTask(
            ManifestTomlEmitter emitter, LocalId id, AuthoredTask task) {
        emitter.namedSection(TASK, id.value());
        task.description().ifPresent(value -> emitter.field(
                FinalManifestCommandFields.TASK_DESCRIPTION,
                string(value)));
        emitter.field(
                FinalManifestCommandFields.TASK_RUN,
                strings(FinalManifestCommandFields.TASK_RUN, task.run()));
        task.cwd().ifPresent(value -> emitter.field(
                FinalManifestCommandFields.TASK_CWD,
                string(value.value())));
        if (!task.env().isEmpty()) {
            emitter.field(FinalManifestCommandFields.TASK_ENV, environment(task.env()));
        }
    }

    private static String environment(Map<EnvironmentVariableName, String> environment) {
        return ManifestTomlValueEncoder.inlineObject(environment.entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> entry.getKey().value(),
                        ManifestModelValues.CODE_POINT_ORDER))
                .map(entry -> ManifestTomlValueEncoder.member(
                        entry.getKey().value(), string(entry.getValue())))
                .toList());
    }

    private static String strings(ManifestField field, List<String> values) {
        return ManifestTomlValueEncoder.fieldArray(field, values.stream()
                .map(ManifestCommandsWriter::string)
                .toList());
    }

    private static String strings(ManifestField field, String key, List<String> values) {
        return ManifestTomlValueEncoder.fieldArray(field, key, values.stream()
                .map(ManifestCommandsWriter::string)
                .toList());
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static <V> List<Map.Entry<LocalId, V>> sorted(Map<LocalId, V> values) {
        return values.entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> entry.getKey().value(),
                        ManifestModelValues.CODE_POINT_ORDER))
                .toList();
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}
