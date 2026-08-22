package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.GeneratedLanguage;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredDeclaredRootStep;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;

/** Decodes authored main and test generated-step unions without resolving references. */
final class ManifestGeneratedStepsDecoder {
    Decoded decode(ManifestDecodeIndex index) {
        return decode(index, (fields, entry, id, step) -> { });
    }

    Decoded decode(ManifestDecodeIndex index, DecodedStepObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Decoded generated-step observer is required.");
        return new Decoded(
                decodeLane(index, ManifestGeneratedStepFields.MAIN, observer),
                decodeLane(index, ManifestGeneratedStepFields.TEST, observer));
    }

    private static Optional<Map<LocalId, AuthoredGeneratedStep>> decodeLane(
            ManifestDecodeIndex index,
            ManifestGeneratedStepFields fields,
            DecodedStepObserver observer) {
        List<ManifestDecodeIndex.SectionEntry> entries = index.sectionEntries(fields.entry());
        if (index.section(fields.collection()).isEmpty() && entries.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashMap<LocalId, AuthoredGeneratedStep> decoded = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry : entries) {
            Row row = new Row(index, entry, fields);
            LocalId id = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new LocalId(entry.key()));
            AuthoredGeneratedStep step = step(row);
            observer.decoded(fields, entry, id, step);
            if (decoded.put(id, step) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate generated step `" + id + "`.");
            }
        }
        return Optional.of(ManifestModelValues.immutableSortedMap(
                decoded, LocalId::compareTo, "Generated-step ID", "Authored generated step"));
    }

    private static AuthoredGeneratedStep step(Row row) {
        ValidatedManifestField kindField = row.required(ManifestGeneratedStepFields.Slot.KIND);
        String kind = ManifestTomlValues.string(kindField);
        return switch (kind) {
            case "openapi" -> openApi(row, row.language());
            case "protobuf" -> protobuf(row, row.language());
            case "exec" -> ManifestGeneratedExecStepDecoder.decode(row, row.language());
            case "declared-root" -> declaredRoot(row, row.language());
            default -> throw new IllegalStateException(
                    "Final manifest schema accepted generated-step kind `" + kind
                            + "` at `" + kindField.path()
                            + "` but the decoder does not recognize it.");
        };
    }

    private static AuthoredOpenApiStep openApi(Row row, Optional<GeneratedLanguage> language) {
        Optional<LocalId> tool = row.optionalId(ManifestGeneratedStepFields.Slot.TOOL);
        row.reject(ManifestGeneratedStepFields.Slot.MAIN_CLASS, ManifestGeneratedStepFields.Slot.ARGS);
        ValidatedManifestField inputField = row.required(ManifestGeneratedStepFields.Slot.INPUT);
        ResourceGlob input = ManifestSemanticDiagnostics.construct(
                inputField, () -> new ResourceGlob(ManifestTomlValues.string(inputField)));
        row.reject(ManifestGeneratedStepFields.Slot.INPUTS);
        Optional<ManifestRelativePath> output = row.optionalPath(ManifestGeneratedStepFields.Slot.OUTPUT);
        row.reject(ManifestGeneratedStepFields.Slot.PRODUCES, ManifestGeneratedStepFields.Slot.INTO);
        Optional<LocalId> preset = row.optionalId(ManifestGeneratedStepFields.Slot.PRESET);
        AuthoredOpenApiOptions overrides = ManifestOpenApiOptionsDecoder.decode(
                row.index(), row.entry(), row.fields().openApiOptions());
        row.reject(
                ManifestGeneratedStepFields.Slot.JAVA_PACKAGE,
                ManifestGeneratedStepFields.Slot.GRPC,
                ManifestGeneratedStepFields.Slot.CACHE,
                ManifestGeneratedStepFields.Slot.CWD,
                ManifestGeneratedStepFields.Slot.ENV,
                ManifestGeneratedStepFields.Slot.SECRET_ENV,
                ManifestGeneratedStepFields.Slot.INHERIT_ENV,
                ManifestGeneratedStepFields.Slot.TIMEOUT_SECONDS);
        GeneratedStepSettings settings = row.settings(language);
        return ManifestSemanticDiagnostics.construct(
                row.entry().section(),
                () -> new AuthoredOpenApiStep(settings, tool, input, output, preset, overrides));
    }

    private static AuthoredProtobufStep protobuf(Row row, Optional<GeneratedLanguage> language) {
        Optional<LocalId> tool = row.optionalId(ManifestGeneratedStepFields.Slot.TOOL);
        row.reject(
                ManifestGeneratedStepFields.Slot.MAIN_CLASS,
                ManifestGeneratedStepFields.Slot.ARGS,
                ManifestGeneratedStepFields.Slot.INPUT);
        ValidatedManifestField inputsField = row.required(ManifestGeneratedStepFields.Slot.INPUTS);
        List<ResourceGlob> inputs = protobufInputs(inputsField);
        Optional<ManifestRelativePath> output = row.optionalPath(ManifestGeneratedStepFields.Slot.OUTPUT);
        row.reject(
                ManifestGeneratedStepFields.Slot.PRODUCES,
                ManifestGeneratedStepFields.Slot.INTO,
                ManifestGeneratedStepFields.Slot.PRESET,
                ManifestGeneratedStepFields.Slot.GENERATOR,
                ManifestGeneratedStepFields.Slot.LIBRARY,
                ManifestGeneratedStepFields.Slot.API_PACKAGE,
                ManifestGeneratedStepFields.Slot.MODEL_PACKAGE,
                ManifestGeneratedStepFields.Slot.INVOKER_PACKAGE,
                ManifestGeneratedStepFields.Slot.CONFIG,
                ManifestGeneratedStepFields.Slot.TEMPLATE_DIR,
                ManifestGeneratedStepFields.Slot.VALIDATE_SPEC,
                ManifestGeneratedStepFields.Slot.OPTIONS,
                ManifestGeneratedStepFields.Slot.ADDITIONAL_PROPERTIES,
                ManifestGeneratedStepFields.Slot.CONFIG_OPTIONS,
                ManifestGeneratedStepFields.Slot.GLOBAL_PROPERTIES,
                ManifestGeneratedStepFields.Slot.TYPE_MAPPINGS,
                ManifestGeneratedStepFields.Slot.IMPORT_MAPPINGS);
        Optional<ValidatedManifestField> packageField = row.field(ManifestGeneratedStepFields.Slot.JAVA_PACKAGE);
        Optional<String> javaPackage = packageField.map(ManifestTomlValues::string);
        packageField.ifPresent(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredProtobufStep(
                        validationSettings(language),
                        tool,
                        inputs,
                        output,
                        javaPackage,
                        Optional.empty())));
        Optional<Boolean> grpc = row.optionalBoolean(ManifestGeneratedStepFields.Slot.GRPC);
        row.reject(
                ManifestGeneratedStepFields.Slot.CACHE,
                ManifestGeneratedStepFields.Slot.CWD,
                ManifestGeneratedStepFields.Slot.ENV,
                ManifestGeneratedStepFields.Slot.SECRET_ENV,
                ManifestGeneratedStepFields.Slot.INHERIT_ENV,
                ManifestGeneratedStepFields.Slot.TIMEOUT_SECONDS);
        GeneratedStepSettings settings = row.settings(language);
        return ManifestSemanticDiagnostics.construct(
                row.entry().section(),
                () -> new AuthoredProtobufStep(settings, tool, inputs, output, javaPackage, grpc));
    }

    private static AuthoredDeclaredRootStep declaredRoot(Row row, Optional<GeneratedLanguage> language) {
        row.reject(
                ManifestGeneratedStepFields.Slot.TOOL,
                ManifestGeneratedStepFields.Slot.MAIN_CLASS,
                ManifestGeneratedStepFields.Slot.ARGS,
                ManifestGeneratedStepFields.Slot.INPUT);
        ValidatedManifestField inputsField = row.required(ManifestGeneratedStepFields.Slot.INPUTS);
        List<ResourceGlob> inputs = declaredInputs(inputsField);
        ValidatedManifestField outputField = row.required(ManifestGeneratedStepFields.Slot.OUTPUT);
        ManifestRelativePath output = ManifestSemanticDiagnostics.construct(
                outputField, () -> new ManifestRelativePath(ManifestTomlValues.string(outputField)));
        row.rejectRange(ManifestGeneratedStepFields.Slot.PRODUCES, ManifestGeneratedStepFields.Slot.TIMEOUT_SECONDS);
        GeneratedStepSettings settings = row.settings(language);
        return ManifestSemanticDiagnostics.construct(
                row.entry().section(),
                () -> new AuthoredDeclaredRootStep(settings, inputs, output));
    }

    private static List<ResourceGlob> protobufInputs(ValidatedManifestField field) {
        List<ResourceGlob> inputs = resourceGlobs(field);
        if (inputs.isEmpty()) {
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredProtobufStep(
                            GeneratedStepSettings.defaultsOmitted(),
                            Optional.empty(),
                            List.of(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }
        for (int index = 0; index < inputs.size(); index++) {
            List<ResourceGlob> prefix = inputs.subList(0, index + 1);
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new AuthoredProtobufStep(
                            GeneratedStepSettings.defaultsOmitted(),
                            Optional.empty(),
                            prefix,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }
        return inputs;
    }

    private static List<ResourceGlob> declaredInputs(ValidatedManifestField field) {
        List<ResourceGlob> inputs = resourceGlobs(field);
        if (inputs.isEmpty()) {
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredDeclaredRootStep(
                            GeneratedStepSettings.defaultsOmitted(),
                            List.of(),
                            new ManifestRelativePath("zolt-validation")));
        }
        for (int index = 0; index < inputs.size(); index++) {
            List<ResourceGlob> prefix = inputs.subList(0, index + 1);
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new AuthoredDeclaredRootStep(
                            GeneratedStepSettings.defaultsOmitted(),
                            prefix,
                            new ManifestRelativePath("zolt-validation")));
        }
        return inputs;
    }

    static List<ResourceGlob> resourceGlobs(ValidatedManifestField field) {
        List<String> values = ManifestTomlValues.strings(field);
        java.util.ArrayList<ResourceGlob> globs = new java.util.ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            int item = index;
            globs.add(ManifestSemanticDiagnostics.construct(
                    field, item, () -> new ResourceGlob(values.get(item))));
        }
        return List.copyOf(globs);
    }

    private static GeneratedStepSettings validationSettings(Optional<GeneratedLanguage> language) {
        return new GeneratedStepSettings(language, Optional.empty(), Optional.empty());
    }

    static <T> T invalid(ValidatedManifestField field, String message) {
        return ManifestSemanticDiagnostics.construct(field, () -> {
            throw new IllegalArgumentException(message);
        });
    }

    record Decoded(
            Optional<Map<LocalId, AuthoredGeneratedStep>> main,
            Optional<Map<LocalId, AuthoredGeneratedStep>> test) {
        Decoded {
            main = Objects.requireNonNull(main, "Main generated steps must not be null.");
            test = Objects.requireNonNull(test, "Test generated steps must not be null.");
        }
    }

    @FunctionalInterface
    interface DecodedStepObserver {
        void decoded(
                ManifestGeneratedStepFields fields,
                ManifestDecodeIndex.SectionEntry entry,
                LocalId id,
                AuthoredGeneratedStep step);
    }

    record Row(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry,
            ManifestGeneratedStepFields fields) {
        Row {
            Objects.requireNonNull(index, "Manifest decode index is required.");
            Objects.requireNonNull(entry, "Generated-step section entry is required.");
            Objects.requireNonNull(fields, "Generated-step fields are required.");
        }

        Optional<ValidatedManifestField> field(ManifestGeneratedStepFields.Slot slot) {
            return index.field(entry, fields.field(slot));
        }

        ValidatedManifestField required(ManifestGeneratedStepFields.Slot slot) {
            return ManifestSemanticDiagnostics.requiredField(index, entry, fields.field(slot));
        }

        Optional<LocalId> optionalId(ManifestGeneratedStepFields.Slot slot) {
            return field(slot).map(value -> ManifestSemanticDiagnostics.construct(
                    value, () -> new LocalId(ManifestTomlValues.string(value))));
        }

        Optional<ManifestRelativePath> optionalPath(ManifestGeneratedStepFields.Slot slot) {
            return field(slot).map(value -> ManifestSemanticDiagnostics.construct(
                    value, () -> new ManifestRelativePath(ManifestTomlValues.string(value))));
        }

        Optional<Boolean> optionalBoolean(ManifestGeneratedStepFields.Slot slot) {
            return field(slot).map(ManifestTomlValues::booleanValue);
        }

        Optional<GeneratedLanguage> language() {
            return field(ManifestGeneratedStepFields.Slot.LANGUAGE).map(value ->
                    generatedLanguage(value, ManifestTomlValues.string(value)));
        }

        GeneratedStepSettings settings(Optional<GeneratedLanguage> language) {
            Optional<Boolean> required = optionalBoolean(ManifestGeneratedStepFields.Slot.REQUIRED);
            Optional<Boolean> clean = optionalBoolean(ManifestGeneratedStepFields.Slot.CLEAN);
            return new GeneratedStepSettings(language, required, clean);
        }

        void reject(ManifestGeneratedStepFields.Slot... slots) {
            for (ManifestGeneratedStepFields.Slot slot : slots) {
                field(slot).ifPresent(value -> invalid(
                        value, "The selected generated-step kind does not allow this field."));
            }
        }

        void rejectRange(
                ManifestGeneratedStepFields.Slot first, ManifestGeneratedStepFields.Slot last) {
            for (int ordinal = first.ordinal(); ordinal <= last.ordinal(); ordinal++) {
                reject(ManifestGeneratedStepFields.Slot.values()[ordinal]);
            }
        }
    }

    private static GeneratedLanguage generatedLanguage(
            ValidatedManifestField field, String value) {
        return ManifestAuthoredSymbols.model(
                field,
                value,
                GeneratedLanguage.values(),
                GeneratedLanguage::configValue,
                "generated language");
    }
}
