package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.toml.schema.ManifestField;

/** Decodes the shared OpenAPI preset and step-local option surface. */
final class ManifestOpenApiOptionsDecoder {
    private ManifestOpenApiOptionsDecoder() {
    }

    static AuthoredOpenApiOptions decode(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry,
            Fields fields) {
        Row row = new Row(index, entry);
        Builder builder = new Builder();
        text(row, builder, fields.generator(), value -> builder.generator = value);
        text(row, builder, fields.library(), value -> builder.library = value);
        text(row, builder, fields.apiPackage(), value -> builder.apiPackage = value);
        text(row, builder, fields.modelPackage(), value -> builder.modelPackage = value);
        text(row, builder, fields.invokerPackage(), value -> builder.invokerPackage = value);
        path(row, builder, fields.config(), value -> builder.config = value);
        path(row, builder, fields.templateDir(), value -> builder.templateDir = value);
        bool(row, builder, fields.validateSpec(), value -> builder.validateSpec = value);
        stringMap(row, builder, fields.options(), value -> builder.options = value);
        stringMap(
                row,
                builder,
                fields.additionalProperties(),
                value -> builder.additionalProperties = value);
        stringMap(row, builder, fields.configOptions(), value -> builder.configOptions = value);
        stringMap(
                row,
                builder,
                fields.globalProperties(),
                value -> builder.globalProperties = value);
        stringMap(row, builder, fields.typeMappings(), value -> builder.typeMappings = value);
        stringMap(row, builder, fields.importMappings(), value -> builder.importMappings = value);
        return ManifestSemanticDiagnostics.construct(entry.section(), builder::build);
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

    record Fields(
            ManifestField generator,
            ManifestField library,
            ManifestField apiPackage,
            ManifestField modelPackage,
            ManifestField invokerPackage,
            ManifestField config,
            ManifestField templateDir,
            ManifestField validateSpec,
            ManifestField options,
            ManifestField additionalProperties,
            ManifestField configOptions,
            ManifestField globalProperties,
            ManifestField typeMappings,
            ManifestField importMappings) {
        Fields {
            Objects.requireNonNull(generator, "OpenAPI generator field is required.");
            Objects.requireNonNull(library, "OpenAPI library field is required.");
            Objects.requireNonNull(apiPackage, "OpenAPI API-package field is required.");
            Objects.requireNonNull(modelPackage, "OpenAPI model-package field is required.");
            Objects.requireNonNull(invokerPackage, "OpenAPI invoker-package field is required.");
            Objects.requireNonNull(config, "OpenAPI config field is required.");
            Objects.requireNonNull(templateDir, "OpenAPI template-directory field is required.");
            Objects.requireNonNull(validateSpec, "OpenAPI validate-spec field is required.");
            Objects.requireNonNull(options, "OpenAPI options field is required.");
            Objects.requireNonNull(
                    additionalProperties, "OpenAPI additional-properties field is required.");
            Objects.requireNonNull(configOptions, "OpenAPI config-options field is required.");
            Objects.requireNonNull(globalProperties, "OpenAPI global-properties field is required.");
            Objects.requireNonNull(typeMappings, "OpenAPI type-mappings field is required.");
            Objects.requireNonNull(importMappings, "OpenAPI import-mappings field is required.");
        }
    }

    private record Row(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        private Row {
            Objects.requireNonNull(index, "Manifest decode index is required.");
            Objects.requireNonNull(entry, "OpenAPI option section entry is required.");
        }

        private Optional<ValidatedManifestField> field(ManifestField handle) {
            return index.field(entry, handle);
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
