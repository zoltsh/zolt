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
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;

/** Decodes authored generated-source presets without composing them into steps. */
final class ManifestGeneratedPresetsDecoder {
    private static final ManifestOpenApiOptionsDecoder.Fields OPEN_API_FIELDS =
            new ManifestOpenApiOptionsDecoder.Fields(
                    GENERATED_PRESET_GENERATOR,
                    GENERATED_PRESET_LIBRARY,
                    GENERATED_PRESET_API_PACKAGE,
                    GENERATED_PRESET_MODEL_PACKAGE,
                    GENERATED_PRESET_INVOKER_PACKAGE,
                    GENERATED_PRESET_CONFIG,
                    GENERATED_PRESET_TEMPLATE_DIR,
                    GENERATED_PRESET_VALIDATE_SPEC,
                    GENERATED_PRESET_OPTIONS,
                    GENERATED_PRESET_ADDITIONAL_PROPERTIES,
                    GENERATED_PRESET_CONFIG_OPTIONS,
                    GENERATED_PRESET_GLOBAL_PROPERTIES,
                    GENERATED_PRESET_TYPE_MAPPINGS,
                    GENERATED_PRESET_IMPORT_MAPPINGS);

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
                AuthoredOpenApiOptions options = ManifestOpenApiOptionsDecoder.decode(
                        index, entry, OPEN_API_FIELDS);
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

    private record Row(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        private Row {
            Objects.requireNonNull(index, "Manifest decode index is required.");
            Objects.requireNonNull(entry, "Generated preset section entry is required.");
        }

        private ValidatedManifestField required(ManifestField handle) {
            return ManifestSemanticDiagnostics.requiredField(index, entry, handle);
        }
    }
}
