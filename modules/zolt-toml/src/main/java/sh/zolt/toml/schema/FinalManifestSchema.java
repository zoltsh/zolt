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
        fields.addAll(List.of(
                generatedStepField(GENERATED_TEST, "kind", ManifestValueKind.STRING, 6_601),
                generatedStepField(GENERATED_TEST, "language", ManifestValueKind.STRING, 6_602),
                generatedStepField(GENERATED_TEST, "tool", ManifestValueKind.STRING, 6_603),
                generatedStepField(GENERATED_TEST, "mainClass", ManifestValueKind.STRING, 6_604),
                generatedStepField(GENERATED_TEST, "args", ManifestValueKind.STRING_ARRAY, 6_605),
                generatedStepField(GENERATED_TEST, "input", ManifestValueKind.STRING, 6_606),
                generatedStepField(GENERATED_TEST, "inputs", ManifestValueKind.STRING_ARRAY, 6_607),
                generatedStepField(GENERATED_TEST, "output", ManifestValueKind.STRING, 6_608),
                generatedStepField(GENERATED_TEST, "produces", ManifestValueKind.STRING, 6_609),
                generatedStepField(GENERATED_TEST, "into", ManifestValueKind.STRING, 6_610),
                generatedStepField(GENERATED_TEST, "preset", ManifestValueKind.STRING, 6_611),
                generatedStepField(GENERATED_TEST, "generator", ManifestValueKind.STRING, 6_612),
                generatedStepField(GENERATED_TEST, "library", ManifestValueKind.STRING, 6_613),
                generatedStepField(GENERATED_TEST, "apiPackage", ManifestValueKind.STRING, 6_614),
                generatedStepField(GENERATED_TEST, "modelPackage", ManifestValueKind.STRING, 6_615),
                generatedStepField(GENERATED_TEST, "invokerPackage", ManifestValueKind.STRING, 6_616),
                generatedStepField(GENERATED_TEST, "config", ManifestValueKind.STRING, 6_617),
                generatedStepField(GENERATED_TEST, "templateDir", ManifestValueKind.STRING, 6_618),
                generatedStepField(GENERATED_TEST, "validateSpec", ManifestValueKind.BOOLEAN, 6_619),
                generatedStepField(GENERATED_TEST, "options", ManifestValueKind.INLINE_TABLE, 6_620),
                generatedStepField(GENERATED_TEST, "additionalProperties", ManifestValueKind.INLINE_TABLE, 6_621),
                generatedStepField(GENERATED_TEST, "configOptions", ManifestValueKind.INLINE_TABLE, 6_622),
                generatedStepField(GENERATED_TEST, "globalProperties", ManifestValueKind.INLINE_TABLE, 6_623),
                generatedStepField(GENERATED_TEST, "typeMappings", ManifestValueKind.INLINE_TABLE, 6_624),
                generatedStepField(GENERATED_TEST, "importMappings", ManifestValueKind.INLINE_TABLE, 6_625),
                generatedStepField(GENERATED_TEST, "javaPackage", ManifestValueKind.STRING, 6_626),
                generatedStepField(GENERATED_TEST, "grpc", ManifestValueKind.BOOLEAN, 6_627),
                generatedStepField(GENERATED_TEST, "cache", ManifestValueKind.STRING, 6_628),
                generatedStepField(GENERATED_TEST, "cwd", ManifestValueKind.STRING, 6_629),
                generatedStepField(GENERATED_TEST, "env", ManifestValueKind.INLINE_TABLE, 6_630),
                generatedStepField(GENERATED_TEST, "secretEnv", ManifestValueKind.INLINE_TABLE, 6_631),
                generatedStepField(GENERATED_TEST, "inheritEnv", ManifestValueKind.STRING_ARRAY, 6_632),
                generatedStepField(GENERATED_TEST, "timeoutSeconds", ManifestValueKind.INTEGER, 6_633),
                generatedStepField(GENERATED_TEST, "required", ManifestValueKind.BOOLEAN, 6_634),
                generatedStepField(GENERATED_TEST, "clean", ManifestValueKind.BOOLEAN, 6_635),
                field(TEST_SOURCES, "java", ManifestValueKind.STRING_ARRAY, 6_701),
                field(TEST_SOURCES, "groovy", ManifestValueKind.STRING_ARRAY, 6_702),
                field(TEST_RUNTIME, "jvmArgs", ManifestValueKind.STRING_ARRAY, 6_711),
                field(TEST_RUNTIME, "properties", ManifestValueKind.INLINE_TABLE, 6_712),
                field(TEST_RUNTIME, "env", ManifestValueKind.INLINE_TABLE, 6_713),
                field(TEST_RUNTIME, "events", ManifestValueKind.STRING_ARRAY, 6_714),
                field(TEST_INTEGRATION, "sources", ManifestValueKind.STRING_ARRAY, 6_721),
                field(TEST_INTEGRATION, "resources", ManifestValueKind.STRING_ARRAY, 6_722),
                field(TEST_SUITE, "classes", ManifestValueKind.STRING_ARRAY, 6_731),
                field(TEST_SUITE, "excludeClasses", ManifestValueKind.STRING_ARRAY, 6_732),
                field(TEST_SUITE, "tags", ManifestValueKind.STRING_ARRAY, 6_733),
                field(TEST_SUITE, "excludeTags", ManifestValueKind.STRING_ARRAY, 6_734),
                field(TEST_SUITE, "workers", ManifestValueKind.INTEGER, 6_735),
                field(TEST_SUITE, "locks", ManifestValueKind.INLINE_TABLE_ARRAY, 6_736),
                field(COVERAGE, "line", ManifestValueKind.NUMBER, 6_910),
                field(COVERAGE, "branch", ManifestValueKind.NUMBER, 6_920),
                field(COVERAGE, "instruction", ManifestValueKind.NUMBER, 6_930),
                field(COVERAGE, "method", ManifestValueKind.NUMBER, 6_940),
                field(PACKAGE, "mode", ManifestValueKind.STRING, 7_001),
                field(PACKAGE, "sources", ManifestValueKind.BOOLEAN, 7_002),
                field(PACKAGE, "javadoc", ManifestValueKind.BOOLEAN, 7_003),
                field(PACKAGE, "testJar", ManifestValueKind.BOOLEAN, 7_004),
                field(PACKAGE, "duplicates", ManifestValueKind.STRING, 7_005),
                field(PACKAGE_MANIFEST, "<attribute>", ManifestValueKind.STRING, 7_011),
                field(BOM, "members", ManifestValueKind.BOOLEAN_OR_STRING_ARRAY, 7_101),
                field(BOM, "exclude", ManifestValueKind.STRING_ARRAY, 7_102),
                mutableMapEntry(
                        BOM_VERSIONS,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        7_111),
                mutableMapEntry(
                        BOM_IMPORTS,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        7_121),
                field(FRAMEWORK_SPRING_BOOT, "native", ManifestValueKind.BOOLEAN, 7_201),
                field(NATIVE, "name", ManifestValueKind.STRING, 7_301),
                field(NATIVE, "output", ManifestValueKind.STRING, 7_302),
                field(NATIVE, "args", ManifestValueKind.STRING_ARRAY, 7_303),
                field(PUBLISH, "release", ManifestValueKind.STRING, 8_001),
                field(PUBLISH, "snapshot", ManifestValueKind.STRING, 8_002),
                field(PUBLISH_REPOSITORY, "url", ManifestValueKind.STRING, 8_101),
                field(PUBLISH_REPOSITORY, "credentials", ManifestValueKind.STRING, 8_102),
                field(PUBLISH_SIGNING, "method", ManifestValueKind.STRING, 8_201),
                field(PUBLISH_SIGNING, "keyId", ManifestValueKind.STRING, 8_202),
                field(PUBLISH_SIGNING, "passphraseEnv", ManifestValueKind.STRING, 8_203),
                field(PUBLISH_CENTRAL, "tokenEnv", ManifestValueKind.STRING, 8_301),
                field(PUBLISH_CENTRAL, "mode", ManifestValueKind.STRING, 8_302),
                field(PUBLISH_CENTRAL, "name", ManifestValueKind.STRING, 8_303),
                field(PUBLISH_CENTRAL, "url", ManifestValueKind.STRING, 8_304),
                field(TASK, "description", ManifestValueKind.STRING, 9_001),
                field(TASK, "run", ManifestValueKind.STRING_ARRAY, 9_002),
                field(TASK, "cwd", ManifestValueKind.STRING, 9_003),
                field(TASK, "env", ManifestValueKind.INLINE_TABLE, 9_004),
                field(ALIASES, "<id>", ManifestValueKind.STRING_ARRAY, 9_101)));
        return List.copyOf(fields);
    }

}
