package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.toml.schema.FinalManifestTestFields;

/** Decodes authored test-process settings without applying runtime defaults. */
final class ManifestTestRuntimeDecoder {
    Optional<AuthoredTestRuntime> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> argsField =
                index.field(FinalManifestTestFields.TEST_RUNTIME_JVM_ARGS);
        Optional<ValidatedManifestField> propertiesField =
                index.field(FinalManifestTestFields.TEST_RUNTIME_PROPERTIES);
        Optional<ValidatedManifestField> envField =
                index.field(FinalManifestTestFields.TEST_RUNTIME_ENV);
        Optional<ValidatedManifestField> eventsField =
                index.field(FinalManifestTestFields.TEST_RUNTIME_EVENTS);
        if (argsField.isEmpty()
                && propertiesField.isEmpty()
                && envField.isEmpty()
                && eventsField.isEmpty()) {
            return Optional.empty();
        }

        List<String> args = argsField
                .map(ManifestTestRuntimeDecoder::arguments)
                .orElse(List.of());
        Map<String, String> properties = propertiesField
                .map(field -> properties(field, args))
                .orElse(Map.of());
        Map<EnvironmentVariableName, String> env = envField
                .map(field -> environment(field, args, properties))
                .orElse(Map.of());
        List<AuthoredTestRuntime.Event> events = eventsField
                .map(field -> events(field, args, properties, env))
                .orElse(List.of());
        ValidatedManifestField anchor = first(
                argsField, propertiesField, envField, eventsField);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredTestRuntime(args, properties, env, events)));
    }

    private static List<String> arguments(ValidatedManifestField field) {
        List<String> raw = ManifestTomlValues.strings(field);
        ArrayList<String> values = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            values.add(raw.get(index));
            List<String> prefix = List.copyOf(values);
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new AuthoredTestRuntime(
                            prefix, Map.of(), Map.of(), List.of()));
        }
        return List.copyOf(values);
    }

    private static Map<String, String> properties(
            ValidatedManifestField field,
            List<String> args) {
        Map<String, String> raw = ManifestSemanticDiagnostics.construct(
                field, () -> ManifestTomlValues.stringMap(field));
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            values.put(key, value);
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> keyed("Property", key, () -> new AuthoredTestRuntime(
                            args, values, Map.of(), List.of())));
        });
        return Map.copyOf(values);
    }

    private static Map<EnvironmentVariableName, String> environment(
            ValidatedManifestField field,
            List<String> args,
            Map<String, String> properties) {
        Map<String, String> raw = ManifestSemanticDiagnostics.construct(
                field, () -> ManifestTomlValues.stringMap(field));
        LinkedHashMap<EnvironmentVariableName, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            EnvironmentVariableName name = ManifestSemanticDiagnostics.construct(
                    field, () -> keyed(
                            "Environment", key, () -> new EnvironmentVariableName(key)));
            values.put(name, value);
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> keyed("Environment", key, () -> new AuthoredTestRuntime(
                            args, properties, values, List.of())));
        });
        return Map.copyOf(values);
    }

    private static List<AuthoredTestRuntime.Event> events(
            ValidatedManifestField field,
            List<String> args,
            Map<String, String> properties,
            Map<EnvironmentVariableName, String> env) {
        List<String> raw = ManifestTomlValues.strings(field);
        ArrayList<AuthoredTestRuntime.Event> values = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            values.add(event(field, raw.get(index)));
            List<AuthoredTestRuntime.Event> prefix = List.copyOf(values);
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new AuthoredTestRuntime(args, properties, env, prefix));
        }
        return List.copyOf(values);
    }

    private static AuthoredTestRuntime.Event event(
            ValidatedManifestField field,
            String value) {
        for (AuthoredTestRuntime.Event event : AuthoredTestRuntime.Event.values()) {
            if (event.configValue().equals(value)) {
                return event;
            }
        }
        throw new IllegalStateException(
                "Final manifest schema accepted test runtime event `" + value
                        + "` at `" + field.path() + "` but the model does not recognize it.");
    }

    @SafeVarargs
    private static ValidatedManifestField first(
            Optional<ValidatedManifestField>... fields) {
        for (Optional<ValidatedManifestField> field : fields) {
            if (field.isPresent()) {
                return field.orElseThrow();
            }
        }
        throw new IllegalStateException("Authored test runtime has no source field.");
    }

    private static <T> T keyed(
            String kind,
            String key,
            Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    kind + " entry `" + key + "`: " + failure.getMessage(), failure);
        }
    }
}
