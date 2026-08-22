package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.SourceRootLanguage;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestTestFields;

/** Composes authored test roots, runtime, integration, and suites without applying defaults. */
final class ManifestTestsDecoder {
    private final ManifestTestRootsDecoder rootsDecoder = new ManifestTestRootsDecoder();
    private final ManifestTestRuntimeDecoder runtimeDecoder = new ManifestTestRuntimeDecoder();
    private final ManifestTestSuitesDecoder suitesDecoder = new ManifestTestSuitesDecoder();

    Optional<AuthoredTests> decode(
            ManifestDecodeIndex index,
            TestsPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored tests presence observer is required.");
        Optional<ValidatedManifestField> firstField = index.firstDirectField(
                FinalManifestPaths.TEST_SOURCES,
                FinalManifestPaths.TEST_RUNTIME,
                FinalManifestPaths.TEST_INTEGRATION);
        if (firstField.isPresent()) {
            ManifestSemanticDiagnostics.construct(
                    firstField.orElseThrow(), () -> notify(observer));
        } else {
            index.section(FinalManifestPaths.TEST_SUITES).ifPresent(section ->
                    ManifestSemanticDiagnostics.construct(section, () -> notify(observer)));
        }
        Optional<AuthoredTests.Sources> sources = rootsDecoder.decodeSources(index);
        Optional<AuthoredTestRuntime> runtime = runtimeDecoder.decode(index);
        Optional<AuthoredTests.Integration> integration = rootsDecoder.decodeIntegration(index);
        Optional<Map<LocalId, AuthoredTestSuite>> suites = suitesDecoder.decode(index);
        if (sources.isEmpty()
                && runtime.isEmpty()
                && integration.isEmpty()
                && suites.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredTests(
                sources,
                runtime,
                integration,
                suites.orElseGet(Map::of)));
    }

    private static AuthoredTests notify(TestsPresenceObserver observer) {
        AuthoredTests tests = AuthoredTests.empty();
        observer.present(tests);
        return tests;
    }

    @FunctionalInterface
    interface TestsPresenceObserver {
        void present(AuthoredTests tests);
    }
}

/** Decodes custom unit and integration test roots without applying conventional defaults. */
final class ManifestTestRootsDecoder {
    Optional<AuthoredTests.Sources> decodeSources(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> javaField =
                index.field(FinalManifestTestFields.TEST_SOURCES_JAVA);
        Optional<ValidatedManifestField> groovyField =
                index.field(FinalManifestTestFields.TEST_SOURCES_GROOVY);
        if (javaField.isEmpty() && groovyField.isEmpty()) {
            return Optional.empty();
        }

        List<ManifestRelativePath> java = javaField
                .map(field -> sourcePaths(
                        field, prefix -> new AuthoredTests.Sources(prefix, List.of())))
                .orElse(List.of());
        List<ManifestRelativePath> groovy = groovyField
                .map(field -> sourcePaths(
                        field, prefix -> new AuthoredTests.Sources(List.of(), prefix)))
                .orElse(List.of());
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.TEST_SOURCES).orElseThrow(),
                () -> new AuthoredTests.Sources(java, groovy)));
    }

    Optional<AuthoredTests.Integration> decodeIntegration(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> sourcesField =
                index.field(FinalManifestTestFields.TEST_INTEGRATION_SOURCES);
        Optional<ValidatedManifestField> resourcesField =
                index.field(FinalManifestTestFields.TEST_INTEGRATION_RESOURCES);
        if (sourcesField.isEmpty() && resourcesField.isEmpty()) {
            return Optional.empty();
        }

        List<ManifestRelativePath> sources = sourcesField
                .map(field -> sourcePaths(
                        field, prefix -> new AuthoredTests.Integration(prefix, List.of())))
                .orElse(List.of());
        List<ManifestRelativePath> resources = resourcesField
                .map(field -> paths(
                        field, prefix -> new AuthoredTests.Integration(List.of(), prefix)))
                .orElse(List.of());
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.TEST_INTEGRATION)
                        .orElseThrow(),
                () -> new AuthoredTests.Integration(sources, resources)));
    }

    /** Test source roots carry the §10.1 language guard; resource roots do not name a language. */
    private static List<ManifestRelativePath> sourcePaths(
            ValidatedManifestField field,
            Function<List<ManifestRelativePath>, Object> probe) {
        return paths(field, probe, SourceRootLanguage::requireSupported);
    }

    private static List<ManifestRelativePath> paths(
            ValidatedManifestField field,
            Function<List<ManifestRelativePath>, Object> probe) {
        return paths(field, probe, UnaryOperator.identity());
    }

    private static List<ManifestRelativePath> paths(
            ValidatedManifestField field,
            Function<List<ManifestRelativePath>, Object> probe,
            UnaryOperator<ManifestRelativePath> guard) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<ManifestRelativePath> paths = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            paths.add(ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> guard.apply(new ManifestRelativePath(authored.get(index)))));
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> probe.apply(paths));
        }
        return List.copyOf(paths);
    }
}

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
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.TEST_RUNTIME)
                        .orElseThrow(() -> new IllegalStateException(
                                "Authored test runtime has no source field.")),
                () -> new AuthoredTestRuntime(args, properties, env, events)));
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
        return ManifestAuthoredSymbols.model(
                field,
                value,
                AuthoredTestRuntime.Event.values(),
                AuthoredTestRuntime.Event::configValue,
                "test runtime event");
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
