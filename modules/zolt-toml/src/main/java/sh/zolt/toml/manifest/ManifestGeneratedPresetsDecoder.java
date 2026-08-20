package sh.zolt.toml.manifest;

import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_ADDITIONAL_PROPERTIES;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_API_PACKAGE;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_CONFIG;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_CONFIG_OPTIONS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_GENERATOR;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_GLOBAL_PROPERTIES;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_IMPORT_MAPPINGS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_INVOKER_PACKAGE;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_KIND;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_LIBRARY;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_MODEL_PACKAGE;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_OPTIONS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_TEMPLATE_DIR;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_TYPE_MAPPINGS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_VALIDATE_SPEC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;

/** Decodes authored generated-source presets without composing them into steps. */
final class ManifestGeneratedPresetsDecoder {
    Optional<AuthoredGeneratedPresets> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return index.section(FinalManifestPaths.GENERATED_PRESETS).map(ignored -> {
            LinkedHashMap<LocalId, AuthoredOpenApiOptions> declarations = new LinkedHashMap<>();
            AuthoredGeneratedPresets presets = AuthoredGeneratedPresets.empty();
            for (ManifestDecodeIndex.SectionEntry entry :
                    index.sectionEntries(FinalManifestPaths.GENERATED_PRESET)) {
                Row row = new Row(index, entry);
                LocalId id = ManifestSemanticDiagnostics.construct(
                        entry.section(), () -> new LocalId(entry.key()));
                requireOpenApiKind(row);
                AuthoredOpenApiOptions options = options(row);
                if (declarations.put(id, options) != null) {
                    throw new IllegalStateException(
                            "Validated manifest contains duplicate generated preset `" + id + "`.");
                }
                presets = ManifestSemanticDiagnostics.construct(
                        entry.section(), () -> new AuthoredGeneratedPresets(declarations));
            }
            return presets;
        });
    }

    private static void requireOpenApiKind(Row row) {
        ValidatedManifestField field = row.required(GENERATED_PRESET_KIND);
        String value = ManifestTomlValues.string(field);
        if (!value.equals("openapi")) {
            throw new IllegalStateException(
                    "Final manifest schema accepted generated-preset kind `" + value
                            + "` at `" + field.path() + "` but the decoder does not recognize it.");
        }
    }

    private static AuthoredOpenApiOptions options(Row row) {
        Builder builder = new Builder();
        text(row, builder, GENERATED_PRESET_GENERATOR, value -> builder.generator = value);
        text(row, builder, GENERATED_PRESET_LIBRARY, value -> builder.library = value);
        text(row, builder, GENERATED_PRESET_API_PACKAGE, value -> builder.apiPackage = value);
        text(row, builder, GENERATED_PRESET_MODEL_PACKAGE, value -> builder.modelPackage = value);
        text(row, builder, GENERATED_PRESET_INVOKER_PACKAGE, value -> builder.invokerPackage = value);
        path(row, builder, GENERATED_PRESET_CONFIG, value -> builder.config = value);
        path(row, builder, GENERATED_PRESET_TEMPLATE_DIR, value -> builder.templateDir = value);
        bool(row, builder, GENERATED_PRESET_VALIDATE_SPEC, value -> builder.validateSpec = value);
        stringMap(row, builder, GENERATED_PRESET_OPTIONS, value -> builder.options = value);
        stringMap(
                row,
                builder,
                GENERATED_PRESET_ADDITIONAL_PROPERTIES,
                value -> builder.additionalProperties = value);
        stringMap(row, builder, GENERATED_PRESET_CONFIG_OPTIONS, value -> builder.configOptions = value);
        stringMap(
                row,
                builder,
                GENERATED_PRESET_GLOBAL_PROPERTIES,
                value -> builder.globalProperties = value);
        stringMap(row, builder, GENERATED_PRESET_TYPE_MAPPINGS, value -> builder.typeMappings = value);
        stringMap(row, builder, GENERATED_PRESET_IMPORT_MAPPINGS, value -> builder.importMappings = value);
        return ManifestSemanticDiagnostics.construct(row.entry().section(), builder::build);
    }

    private static void text(
            Row row,
            Builder builder,
            ManifestField handle,
            Consumer<Optional<String>> setter) {
        row.field(handle).ifPresent(field -> {
            setter.accept(Optional.of(ManifestTomlValues.string(field)));
            ManifestSemanticDiagnostics.construct(field, builder::build);
        });
    }

    private static void path(
            Row row,
            Builder builder,
            ManifestField handle,
            Consumer<Optional<ManifestRelativePath>> setter) {
        row.field(handle).ifPresent(field -> {
            ManifestRelativePath value = ManifestSemanticDiagnostics.construct(
                    field, () -> new ManifestRelativePath(ManifestTomlValues.string(field)));
            setter.accept(Optional.of(value));
            ManifestSemanticDiagnostics.construct(field, builder::build);
        });
    }

    private static void bool(
            Row row,
            Builder builder,
            ManifestField handle,
            Consumer<Optional<Boolean>> setter) {
        row.field(handle).ifPresent(field -> {
            setter.accept(Optional.of(ManifestTomlValues.booleanValue(field)));
            ManifestSemanticDiagnostics.construct(field, builder::build);
        });
    }

    private static void stringMap(
            Row row,
            Builder builder,
            ManifestField handle,
            Consumer<Map<String, String>> setter) {
        row.field(handle).ifPresent(field -> {
            Map<String, String> value = ManifestSemanticDiagnostics.construct(
                    field, () -> ManifestTomlValues.stringMap(field));
            setter.accept(value);
            ManifestSemanticDiagnostics.construct(field, builder::build);
        });
    }

    private record Row(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        private Row {
            Objects.requireNonNull(index, "Manifest decode index is required.");
            Objects.requireNonNull(entry, "Generated preset section entry is required.");
        }

        private Optional<ValidatedManifestField> field(ManifestField handle) {
            return index.field(entry, handle);
        }

        private ValidatedManifestField required(ManifestField handle) {
            return ManifestSemanticDiagnostics.requiredField(index, entry, handle);
        }
    }

    private static final class Builder {
        private Optional<String> generator = Optional.empty();
        private Optional<String> library = Optional.empty();
        private Optional<String> apiPackage = Optional.empty();
        private Optional<String> modelPackage = Optional.empty();
        private Optional<String> invokerPackage = Optional.empty();
        private Optional<ManifestRelativePath> config = Optional.empty();
        private Optional<ManifestRelativePath> templateDir = Optional.empty();
        private Optional<Boolean> validateSpec = Optional.empty();
        private Map<String, String> options = Map.of();
        private Map<String, String> additionalProperties = Map.of();
        private Map<String, String> configOptions = Map.of();
        private Map<String, String> globalProperties = Map.of();
        private Map<String, String> typeMappings = Map.of();
        private Map<String, String> importMappings = Map.of();

        private AuthoredOpenApiOptions build() {
            return new AuthoredOpenApiOptions(
                    generator,
                    library,
                    apiPackage,
                    modelPackage,
                    invokerPackage,
                    config,
                    templateDir,
                    validateSpec,
                    options,
                    additionalProperties,
                    configOptions,
                    globalProperties,
                    typeMappings,
                    importMappings);
        }
    }
}
