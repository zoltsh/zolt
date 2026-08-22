package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;

/** Exact canonical field handles for one generated-step lane. */
final class ManifestGeneratedStepFields {
    static final ManifestGeneratedStepFields MAIN = new ManifestGeneratedStepFields(
            FinalManifestPaths.GENERATED_MAIN_STEPS,
            FinalManifestPaths.GENERATED_MAIN);

    static final ManifestGeneratedStepFields TEST = new ManifestGeneratedStepFields(
            FinalManifestPaths.GENERATED_TEST_STEPS,
            FinalManifestPaths.GENERATED_TEST);

    private final ManifestPath collection;
    private final ManifestPath entry;
    private final List<ManifestField> handles;

    private ManifestGeneratedStepFields(
            ManifestPath collection,
            ManifestPath entry) {
        this.collection = Objects.requireNonNull(
                collection, "Generated-step collection path is required.");
        this.entry = Objects.requireNonNull(entry, "Generated-step entry path is required.");
        List<String> parent = this.entry.segments();
        this.handles = FinalManifestSchema.registry().fields().stream()
                .filter(field -> field.path().segments().size() == parent.size() + 1)
                .filter(field -> field.path().segments()
                        .subList(0, parent.size())
                        .equals(parent))
                .toList();
        if (this.handles.size() != Slot.values().length) {
            throw new IllegalStateException("Generated-step lane must declare every canonical field.");
        }
        for (Slot slot : Slot.values()) {
            ManifestField handle = this.handles.get(slot.ordinal());
            ManifestPath expected = this.entry.child(slot.fieldName());
            if (!handle.path().equals(expected)
                    || FinalManifestSchema.registry().field(expected).orElseThrow() != handle) {
                throw new IllegalStateException(
                        "Generated-step schema order does not match canonical field slots.");
            }
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
        CLEAN;

        String fieldName() {
            String[] words = name().toLowerCase(Locale.ROOT).split("_");
            StringBuilder fieldName = new StringBuilder(words[0]);
            for (int index = 1; index < words.length; index++) {
                String word = words[index];
                fieldName.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
            return fieldName.toString();
        }
    }
}
