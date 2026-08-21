package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.GeneratedCachePolicy;
import sh.zolt.manifest.GeneratedLanguage;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredExecStep;

/** Decodes {@code kind = "exec"} generated steps without resolving their tool. */
final class ManifestGeneratedExecStepDecoder {
    private static final LocalId PROJECT = new LocalId("project");
    private static final ResourceGlob VALIDATION_INPUT = new ResourceGlob("zolt-validation");
    private static final ManifestRelativePath VALIDATION_OUTPUT =
            new ManifestRelativePath("zolt-validation");

    private ManifestGeneratedExecStepDecoder() {
    }

    static AuthoredExecStep decode(
            ManifestGeneratedStepsDecoder.Row row,
            Optional<GeneratedLanguage> language) {
        ValidatedManifestField toolField = row.required(ManifestGeneratedStepFields.Slot.TOOL);
        LocalId tool = ManifestSemanticDiagnostics.construct(
                toolField, () -> new LocalId(ManifestTomlValues.string(toolField)));
        Optional<ValidatedManifestField> mainClassField = row.field(
                ManifestGeneratedStepFields.Slot.MAIN_CLASS);
        Optional<JavaBinaryClassName> mainClass = mainClassField.map(field ->
                ManifestSemanticDiagnostics.construct(
                        field, () -> new JavaBinaryClassName(ManifestTomlValues.string(field))));
        validateMainClass(row, tool, mainClass, mainClassField);

        Optional<ValidatedManifestField> argsField = row.field(ManifestGeneratedStepFields.Slot.ARGS);
        List<String> args = argsField.map(field -> arguments(field, tool, mainClass)).orElse(List.of());
        row.reject(ManifestGeneratedStepFields.Slot.INPUT);
        ValidatedManifestField inputsField = row.required(ManifestGeneratedStepFields.Slot.INPUTS);
        List<ResourceGlob> inputs = inputs(inputsField, tool, mainClass, args);
        ValidatedManifestField outputField = row.required(ManifestGeneratedStepFields.Slot.OUTPUT);
        ManifestRelativePath output = ManifestSemanticDiagnostics.construct(
                outputField,
                () -> new ManifestRelativePath(ManifestTomlValues.string(outputField)));
        ValidatedManifestField producesField = row.required(ManifestGeneratedStepFields.Slot.PRODUCES);
        GeneratedOutputKind produces = outputKind(
                producesField, ManifestTomlValues.string(producesField));
        Optional<ValidatedManifestField> intoField = row.field(ManifestGeneratedStepFields.Slot.INTO);
        Optional<ManifestRelativePath> into = intoField.map(field ->
                ManifestSemanticDiagnostics.construct(
                        field, () -> new ManifestRelativePath(ManifestTomlValues.string(field))));
        Composition composition = new Composition(
                tool, mainClass, args, inputs, output, produces, into);
        intoField.ifPresent(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> composition.build(validationSettings(language))));

        row.rejectRange(ManifestGeneratedStepFields.Slot.PRESET, ManifestGeneratedStepFields.Slot.GRPC);
        composition.cache = row.field(ManifestGeneratedStepFields.Slot.CACHE)
                .map(field -> cachePolicy(field, ManifestTomlValues.string(field)));
        composition.cwd = row.optionalPath(ManifestGeneratedStepFields.Slot.CWD);
        row.field(ManifestGeneratedStepFields.Slot.ENV)
                .ifPresent(field -> environment(field, composition));
        row.field(ManifestGeneratedStepFields.Slot.SECRET_ENV)
                .ifPresent(field -> secretEnvironment(field, composition));
        row.field(ManifestGeneratedStepFields.Slot.INHERIT_ENV)
                .ifPresent(field -> inheritedEnvironment(field, composition));
        row.field(ManifestGeneratedStepFields.Slot.TIMEOUT_SECONDS)
                .ifPresent(field -> timeout(field, composition));
        GeneratedStepSettings settings = row.settings(language);
        return ManifestSemanticDiagnostics.construct(
                row.entry().section(), () -> composition.build(settings));
    }

    private static void validateMainClass(
            ManifestGeneratedStepsDecoder.Row row,
            LocalId tool,
            Optional<JavaBinaryClassName> mainClass,
            Optional<ValidatedManifestField> field) {
        if (tool.equals(PROJECT) && mainClass.isEmpty()) {
            row.required(ManifestGeneratedStepFields.Slot.MAIN_CLASS);
        }
        field.ifPresent(value -> ManifestSemanticDiagnostics.construct(
                value,
                () -> validationStep(tool, mainClass, List.of(), List.of(VALIDATION_INPUT))));
    }

    private static List<String> arguments(
            ValidatedManifestField field,
            LocalId tool,
            Optional<JavaBinaryClassName> mainClass) {
        List<String> args = ManifestTomlValues.strings(field);
        for (int index = 0; index < args.size(); index++) {
            List<String> prefix = args.subList(0, index + 1);
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> validationStep(tool, mainClass, prefix, List.of(VALIDATION_INPUT)));
        }
        return args;
    }

    private static List<ResourceGlob> inputs(
            ValidatedManifestField field,
            LocalId tool,
            Optional<JavaBinaryClassName> mainClass,
            List<String> args) {
        List<ResourceGlob> inputs = ManifestGeneratedStepsDecoder.resourceGlobs(field);
        if (inputs.isEmpty()) {
            ManifestSemanticDiagnostics.construct(
                    field, () -> validationStep(tool, mainClass, args, List.of()));
        }
        for (int index = 0; index < inputs.size(); index++) {
            List<ResourceGlob> prefix = inputs.subList(0, index + 1);
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> validationStep(tool, mainClass, args, prefix));
        }
        return inputs;
    }

    private static void environment(
            ValidatedManifestField field,
            Composition composition) {
        Map<String, String> raw = ManifestSemanticDiagnostics.construct(
                field, () -> ManifestTomlValues.stringMap(field));
        LinkedHashMap<EnvironmentVariableName, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            EnvironmentVariableName name = ManifestSemanticDiagnostics.construct(
                    field, () -> keyed(key, () -> new EnvironmentVariableName(key)));
            values.put(name, value);
            composition.env = values;
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> keyed(key, () -> composition.build(
                            GeneratedStepSettings.defaultsOmitted())));
        });
        composition.env = Map.copyOf(values);
    }

    private static void secretEnvironment(
            ValidatedManifestField field,
            Composition composition) {
        Map<String, String> raw = ManifestSemanticDiagnostics.construct(
                field, () -> ManifestTomlValues.stringMap(field));
        LinkedHashMap<EnvironmentVariableName, EnvironmentVariableName> values =
                new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            EnvironmentVariableName target = ManifestSemanticDiagnostics.construct(
                    field, () -> keyed(key, () -> new EnvironmentVariableName(key)));
            EnvironmentVariableName source = ManifestSemanticDiagnostics.construct(
                    field, () -> keyed(key, () -> new EnvironmentVariableName(value)));
            values.put(target, source);
            composition.secretEnv = values;
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> keyed(key, () -> composition.build(
                            GeneratedStepSettings.defaultsOmitted())));
        });
        composition.secretEnv = Map.copyOf(values);
    }

    private static void inheritedEnvironment(
            ValidatedManifestField field,
            Composition composition) {
        List<String> raw = ManifestTomlValues.strings(field);
        ArrayList<EnvironmentVariableName> values = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            int item = index;
            values.add(ManifestSemanticDiagnostics.construct(
                    field, item, () -> new EnvironmentVariableName(raw.get(item))));
            List<EnvironmentVariableName> prefix = List.copyOf(values);
            composition.inheritEnv = prefix;
            ManifestSemanticDiagnostics.construct(
                    field,
                    item,
                    () -> composition.build(GeneratedStepSettings.defaultsOmitted()));
        }
        composition.inheritEnv = List.copyOf(values);
    }

    private static void timeout(
            ValidatedManifestField field,
            Composition composition) {
        int decoded = ManifestSemanticDiagnostics.construct(field, () -> {
            long value = ManifestTomlValues.integer(field);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Exec step timeoutSeconds must fit a signed 32-bit integer.");
            }
            return (int) value;
        });
        composition.timeout = Optional.of(decoded);
        ManifestSemanticDiagnostics.construct(
                field, () -> composition.build(GeneratedStepSettings.defaultsOmitted()));
    }

    private static AuthoredExecStep validationStep(
            LocalId tool,
            Optional<JavaBinaryClassName> mainClass,
            List<String> args,
            List<ResourceGlob> inputs) {
        return new AuthoredExecStep(
                GeneratedStepSettings.defaultsOmitted(), tool, mainClass, args, inputs,
                VALIDATION_OUTPUT, GeneratedOutputKind.JAVA_SOURCES, Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of(), Optional.empty());
    }

    private static final class Composition {
        private final LocalId tool;
        private final Optional<JavaBinaryClassName> mainClass;
        private final List<String> args;
        private final List<ResourceGlob> inputs;
        private final ManifestRelativePath output;
        private final GeneratedOutputKind produces;
        private final Optional<ManifestRelativePath> into;
        private Optional<GeneratedCachePolicy> cache = Optional.empty();
        private Optional<ManifestRelativePath> cwd = Optional.empty();
        private Map<EnvironmentVariableName, String> env = Map.of();
        private Map<EnvironmentVariableName, EnvironmentVariableName> secretEnv = Map.of();
        private List<EnvironmentVariableName> inheritEnv = List.of();
        private Optional<Integer> timeout = Optional.empty();

        private Composition(
                LocalId tool,
                Optional<JavaBinaryClassName> mainClass,
                List<String> args,
                List<ResourceGlob> inputs,
                ManifestRelativePath output,
                GeneratedOutputKind produces,
                Optional<ManifestRelativePath> into) {
            this.tool = tool;
            this.mainClass = mainClass;
            this.args = args;
            this.inputs = inputs;
            this.output = output;
            this.produces = produces;
            this.into = into;
        }

        private AuthoredExecStep build(GeneratedStepSettings settings) {
            return new AuthoredExecStep(
                    settings, tool, mainClass, args, inputs, output, produces, into, cache, cwd,
                    env, secretEnv, inheritEnv, timeout);
        }
    }

    private static GeneratedOutputKind outputKind(ValidatedManifestField field, String value) {
        return ManifestAuthoredSymbols.model(
                field,
                value,
                GeneratedOutputKind.values(),
                GeneratedOutputKind::configValue,
                "generated output kind");
    }

    private static GeneratedCachePolicy cachePolicy(ValidatedManifestField field, String value) {
        return ManifestAuthoredSymbols.model(
                field,
                value,
                GeneratedCachePolicy.values(),
                GeneratedCachePolicy::configValue,
                "generated cache policy");
    }

    private static GeneratedStepSettings validationSettings(
            Optional<GeneratedLanguage> language) {
        return new GeneratedStepSettings(language, Optional.empty(), Optional.empty());
    }

    private static <T> T keyed(String key, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Environment entry `" + key + "`: " + failure.getMessage(), failure);
        }
    }
}
