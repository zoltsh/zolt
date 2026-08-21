package sh.zolt.toml.schema;

import static sh.zolt.toml.schema.FinalManifestFieldFactory.*;
import static sh.zolt.toml.schema.FinalManifestPaths.*;

import java.util.ArrayList;
import java.util.List;

/** The final manifest schema catalog, populated incrementally by frozen contract domain. */
public final class FinalManifestSchema {
    private static final ManifestSchemaRegistry REGISTRY = createRegistry();

    private FinalManifestSchema() {
    }

    public static ManifestSchemaRegistry registry() {
        return REGISTRY;
    }

    private static ManifestSchemaRegistry createRegistry() {
        List<ManifestField> fields = fields();
        FinalManifestFieldSemantics.validateCatalog(fields);
        return new ManifestSchemaRegistry(
                fields,
                FinalManifestSections.sections(),
                FinalManifestSymbols.registry());
    }

    private static List<ManifestField> fields() {
        ArrayList<ManifestField> fields = new ArrayList<>();
        fields.addAll(FinalManifestIdentityFields.fields());
        fields.addAll(FinalManifestToolchainFields.fields());
        fields.addAll(FinalManifestSharedFields.fields());
        fields.addAll(FinalManifestDependencyFields.fields());
        fields.addAll(FinalManifestBuildFields.fields());
        fields.addAll(FinalManifestCompilerFields.fields());
        fields.addAll(FinalManifestResourceFields.fields());
        fields.addAll(FinalManifestGeneratedToolFields.fields());
        fields.addAll(FinalManifestGeneratedPresetFields.fields());
        fields.addAll(FinalManifestGeneratedMainFields.fields());
        fields.addAll(FinalManifestGeneratedTestFields.fields());
        fields.addAll(FinalManifestTestFields.fields());
        fields.addAll(FinalManifestCoverageFields.fields());
        fields.addAll(FinalManifestPackagingFields.fields());
        fields.addAll(FinalManifestPublishingFields.fields());
        fields.addAll(List.of(
                field(TASK, "description", ManifestValueKind.STRING, 9_001),
                field(TASK, "run", ManifestValueKind.STRING_ARRAY, 9_002),
                field(TASK, "cwd", ManifestValueKind.STRING, 9_003),
                field(TASK, "env", ManifestValueKind.INLINE_TABLE, 9_004),
                field(ALIASES, "<id>", ManifestValueKind.STRING_ARRAY, 9_101)));
        return List.copyOf(fields);
    }

}
