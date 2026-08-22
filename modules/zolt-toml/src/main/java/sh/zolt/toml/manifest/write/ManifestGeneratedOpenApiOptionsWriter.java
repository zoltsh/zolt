package sh.zolt.toml.manifest.write;

import java.util.Map;
import java.util.Objects;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.toml.schema.ManifestField;

/** Emits one canonical set of preset or step-local OpenAPI options. */
final class ManifestGeneratedOpenApiOptionsWriter {
    private ManifestGeneratedOpenApiOptionsWriter() {
    }

    static void write(
            ManifestTomlEmitter emitter,
            AuthoredOpenApiOptions options,
            Fields fields) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(options, "Authored OpenAPI options are required.");
        Objects.requireNonNull(fields, "OpenAPI field handles are required.");
        options.generator().ifPresent(value -> emitter.field(fields.generator(), string(value)));
        options.library().ifPresent(value -> emitter.field(fields.library(), string(value)));
        options.apiPackage().ifPresent(value -> emitter.field(fields.apiPackage(), string(value)));
        options.modelPackage().ifPresent(value -> emitter.field(fields.modelPackage(), string(value)));
        options.invokerPackage().ifPresent(value -> emitter.field(fields.invokerPackage(), string(value)));
        options.config().ifPresent(value -> emitter.field(fields.config(), string(value.value())));
        options.templateDir().ifPresent(value -> emitter.field(
                fields.templateDir(), string(value.value())));
        options.validateSpec().filter(value -> !value).ifPresent(value -> emitter.field(
                fields.validateSpec(), ManifestTomlValueEncoder.booleanValue(value)));
        writeMap(emitter, fields.options(), options.options());
        writeMap(emitter, fields.additionalProperties(), options.additionalProperties());
        writeMap(emitter, fields.configOptions(), options.configOptions());
        writeMap(emitter, fields.globalProperties(), options.globalProperties());
        writeMap(emitter, fields.typeMappings(), options.typeMappings());
        writeMap(emitter, fields.importMappings(), options.importMappings());
    }

    private static void writeMap(
            ManifestTomlEmitter emitter, ManifestField field, Map<String, String> values) {
        if (!values.isEmpty()) {
            emitter.field(field, ManifestGeneratedWriterValues.stringMap(values));
        }
    }

    private static String string(String value) {
        return ManifestGeneratedWriterValues.string(value);
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
            Objects.requireNonNull(additionalProperties, "OpenAPI additional-properties field is required.");
            Objects.requireNonNull(configOptions, "OpenAPI config-options field is required.");
            Objects.requireNonNull(globalProperties, "OpenAPI global-properties field is required.");
            Objects.requireNonNull(typeMappings, "OpenAPI type-mappings field is required.");
            Objects.requireNonNull(importMappings, "OpenAPI import-mappings field is required.");
        }
    }
}
