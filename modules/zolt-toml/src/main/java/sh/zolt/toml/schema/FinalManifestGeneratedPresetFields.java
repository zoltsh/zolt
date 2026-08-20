package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored generated-source presets. */
public final class FinalManifestGeneratedPresetFields {
    public static final ManifestField GENERATED_PRESET_KIND = field(
            FinalManifestPaths.GENERATED_PRESET, "kind", ManifestValueKind.STRING, 6_401);
    public static final ManifestField GENERATED_PRESET_GENERATOR = field(
            FinalManifestPaths.GENERATED_PRESET, "generator", ManifestValueKind.STRING, 6_402);
    public static final ManifestField GENERATED_PRESET_LIBRARY = field(
            FinalManifestPaths.GENERATED_PRESET, "library", ManifestValueKind.STRING, 6_403);
    public static final ManifestField GENERATED_PRESET_API_PACKAGE = field(
            FinalManifestPaths.GENERATED_PRESET, "apiPackage", ManifestValueKind.STRING, 6_404);
    public static final ManifestField GENERATED_PRESET_MODEL_PACKAGE = field(
            FinalManifestPaths.GENERATED_PRESET, "modelPackage", ManifestValueKind.STRING, 6_405);
    public static final ManifestField GENERATED_PRESET_INVOKER_PACKAGE = field(
            FinalManifestPaths.GENERATED_PRESET, "invokerPackage", ManifestValueKind.STRING, 6_406);
    public static final ManifestField GENERATED_PRESET_CONFIG = field(
            FinalManifestPaths.GENERATED_PRESET, "config", ManifestValueKind.STRING, 6_407);
    public static final ManifestField GENERATED_PRESET_TEMPLATE_DIR = field(
            FinalManifestPaths.GENERATED_PRESET, "templateDir", ManifestValueKind.STRING, 6_408);
    public static final ManifestField GENERATED_PRESET_VALIDATE_SPEC = field(
            FinalManifestPaths.GENERATED_PRESET, "validateSpec", ManifestValueKind.BOOLEAN, 6_409);
    public static final ManifestField GENERATED_PRESET_OPTIONS = field(
            FinalManifestPaths.GENERATED_PRESET, "options", ManifestValueKind.INLINE_TABLE, 6_410);
    public static final ManifestField GENERATED_PRESET_ADDITIONAL_PROPERTIES = field(
            FinalManifestPaths.GENERATED_PRESET,
            "additionalProperties",
            ManifestValueKind.INLINE_TABLE,
            6_411);
    public static final ManifestField GENERATED_PRESET_CONFIG_OPTIONS = field(
            FinalManifestPaths.GENERATED_PRESET, "configOptions", ManifestValueKind.INLINE_TABLE, 6_412);
    public static final ManifestField GENERATED_PRESET_GLOBAL_PROPERTIES = field(
            FinalManifestPaths.GENERATED_PRESET,
            "globalProperties",
            ManifestValueKind.INLINE_TABLE,
            6_413);
    public static final ManifestField GENERATED_PRESET_TYPE_MAPPINGS = field(
            FinalManifestPaths.GENERATED_PRESET, "typeMappings", ManifestValueKind.INLINE_TABLE, 6_414);
    public static final ManifestField GENERATED_PRESET_IMPORT_MAPPINGS = field(
            FinalManifestPaths.GENERATED_PRESET, "importMappings", ManifestValueKind.INLINE_TABLE, 6_415);

    private FinalManifestGeneratedPresetFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                GENERATED_PRESET_KIND,
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
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }
}
