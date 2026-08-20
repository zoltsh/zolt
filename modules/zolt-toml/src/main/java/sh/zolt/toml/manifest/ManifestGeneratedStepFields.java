package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Objects;
import sh.zolt.toml.schema.FinalManifestGeneratedMainFields;
import sh.zolt.toml.schema.FinalManifestGeneratedTestFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;

/** Exact canonical field handles for one generated-step lane. */
final class ManifestGeneratedStepFields {
    static final ManifestGeneratedStepFields MAIN = new ManifestGeneratedStepFields(
            FinalManifestPaths.GENERATED_MAIN_STEPS,
            FinalManifestPaths.GENERATED_MAIN,
            List.of(
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_KIND,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_LANGUAGE,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_TOOL,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_MAIN_CLASS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_ARGS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_INPUT,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_INPUTS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_OUTPUT,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_PRODUCES,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_INTO,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_PRESET,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_GENERATOR,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_LIBRARY,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_API_PACKAGE,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_MODEL_PACKAGE,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_INVOKER_PACKAGE,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_CONFIG,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_TEMPLATE_DIR,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_VALIDATE_SPEC,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_OPTIONS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_ADDITIONAL_PROPERTIES,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_CONFIG_OPTIONS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_GLOBAL_PROPERTIES,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_TYPE_MAPPINGS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_IMPORT_MAPPINGS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_JAVA_PACKAGE,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_GRPC,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_CACHE,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_CWD,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_ENV,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_SECRET_ENV,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_INHERIT_ENV,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_TIMEOUT_SECONDS,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_REQUIRED,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_CLEAN));

    static final ManifestGeneratedStepFields TEST = new ManifestGeneratedStepFields(
            FinalManifestPaths.GENERATED_TEST_STEPS,
            FinalManifestPaths.GENERATED_TEST,
            List.of(
                    FinalManifestGeneratedTestFields.GENERATED_TEST_KIND,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_LANGUAGE,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_TOOL,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_MAIN_CLASS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_ARGS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_INPUT,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_INPUTS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_OUTPUT,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_PRODUCES,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_INTO,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_PRESET,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_GENERATOR,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_LIBRARY,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_API_PACKAGE,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_MODEL_PACKAGE,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_INVOKER_PACKAGE,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_CONFIG,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_TEMPLATE_DIR,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_VALIDATE_SPEC,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_OPTIONS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_ADDITIONAL_PROPERTIES,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_CONFIG_OPTIONS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_GLOBAL_PROPERTIES,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_TYPE_MAPPINGS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_IMPORT_MAPPINGS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_JAVA_PACKAGE,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_GRPC,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_CACHE,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_CWD,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_ENV,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_SECRET_ENV,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_INHERIT_ENV,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_TIMEOUT_SECONDS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_REQUIRED,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_CLEAN));

    private final ManifestPath collection;
    private final ManifestPath entry;
    private final List<ManifestField> handles;

    private ManifestGeneratedStepFields(
            ManifestPath collection,
            ManifestPath entry,
            List<ManifestField> handles) {
        this.collection = Objects.requireNonNull(
                collection, "Generated-step collection path is required.");
        this.entry = Objects.requireNonNull(entry, "Generated-step entry path is required.");
        this.handles = List.copyOf(handles);
        if (this.handles.size() != Slot.values().length) {
            throw new IllegalStateException("Generated-step lane must declare every canonical field.");
        }
    }

    ManifestPath collection() {
        return collection;
    }

    ManifestPath entry() {
        return entry;
    }

    ManifestField field(Slot slot) {
        return handles.get(Objects.requireNonNull(slot, "Generated-step field slot is required.").ordinal());
    }

    List<ManifestField> handles() {
        return handles;
    }

    ManifestOpenApiOptionsDecoder.Fields openApiOptions() {
        return new ManifestOpenApiOptionsDecoder.Fields(
                field(Slot.GENERATOR),
                field(Slot.LIBRARY),
                field(Slot.API_PACKAGE),
                field(Slot.MODEL_PACKAGE),
                field(Slot.INVOKER_PACKAGE),
                field(Slot.CONFIG),
                field(Slot.TEMPLATE_DIR),
                field(Slot.VALIDATE_SPEC),
                field(Slot.OPTIONS),
                field(Slot.ADDITIONAL_PROPERTIES),
                field(Slot.CONFIG_OPTIONS),
                field(Slot.GLOBAL_PROPERTIES),
                field(Slot.TYPE_MAPPINGS),
                field(Slot.IMPORT_MAPPINGS));
    }

    enum Slot {
        KIND,
        LANGUAGE,
        TOOL,
        MAIN_CLASS,
        ARGS,
        INPUT,
        INPUTS,
        OUTPUT,
        PRODUCES,
        INTO,
        PRESET,
        GENERATOR,
        LIBRARY,
        API_PACKAGE,
        MODEL_PACKAGE,
        INVOKER_PACKAGE,
        CONFIG,
        TEMPLATE_DIR,
        VALIDATE_SPEC,
        OPTIONS,
        ADDITIONAL_PROPERTIES,
        CONFIG_OPTIONS,
        GLOBAL_PROPERTIES,
        TYPE_MAPPINGS,
        IMPORT_MAPPINGS,
        JAVA_PACKAGE,
        GRPC,
        CACHE,
        CWD,
        ENV,
        SECRET_ENV,
        INHERIT_ENV,
        TIMEOUT_SECONDS,
        REQUIRED,
        CLEAN
    }
}
