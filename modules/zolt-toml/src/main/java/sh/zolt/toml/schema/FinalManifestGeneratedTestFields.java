package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored test generated-source steps. */
public final class FinalManifestGeneratedTestFields {
    public static final ManifestField GENERATED_TEST_KIND = field(
            "kind", ManifestValueKind.STRING, 6_601);
    public static final ManifestField GENERATED_TEST_LANGUAGE = field(
            "language", ManifestValueKind.STRING, 6_602);
    public static final ManifestField GENERATED_TEST_TOOL = field(
            "tool", ManifestValueKind.STRING, 6_603);
    public static final ManifestField GENERATED_TEST_MAIN_CLASS = field(
            "mainClass", ManifestValueKind.STRING, 6_604);
    public static final ManifestField GENERATED_TEST_ARGS = field(
            "args", ManifestValueKind.STRING_ARRAY, 6_605);
    public static final ManifestField GENERATED_TEST_INPUT = field(
            "input", ManifestValueKind.STRING, 6_606);
    public static final ManifestField GENERATED_TEST_INPUTS = field(
            "inputs", ManifestValueKind.STRING_ARRAY, 6_607);
    public static final ManifestField GENERATED_TEST_OUTPUT = field(
            "output", ManifestValueKind.STRING, 6_608);
    public static final ManifestField GENERATED_TEST_PRODUCES = field(
            "produces", ManifestValueKind.STRING, 6_609);
    public static final ManifestField GENERATED_TEST_INTO = field(
            "into", ManifestValueKind.STRING, 6_610);
    public static final ManifestField GENERATED_TEST_PRESET = field(
            "preset", ManifestValueKind.STRING, 6_611);
    public static final ManifestField GENERATED_TEST_GENERATOR = field(
            "generator", ManifestValueKind.STRING, 6_612);
    public static final ManifestField GENERATED_TEST_LIBRARY = field(
            "library", ManifestValueKind.STRING, 6_613);
    public static final ManifestField GENERATED_TEST_API_PACKAGE = field(
            "apiPackage", ManifestValueKind.STRING, 6_614);
    public static final ManifestField GENERATED_TEST_MODEL_PACKAGE = field(
            "modelPackage", ManifestValueKind.STRING, 6_615);
    public static final ManifestField GENERATED_TEST_INVOKER_PACKAGE = field(
            "invokerPackage", ManifestValueKind.STRING, 6_616);
    public static final ManifestField GENERATED_TEST_CONFIG = field(
            "config", ManifestValueKind.STRING, 6_617);
    public static final ManifestField GENERATED_TEST_TEMPLATE_DIR = field(
            "templateDir", ManifestValueKind.STRING, 6_618);
    public static final ManifestField GENERATED_TEST_VALIDATE_SPEC = field(
            "validateSpec", ManifestValueKind.BOOLEAN, 6_619);
    public static final ManifestField GENERATED_TEST_OPTIONS = field(
            "options", ManifestValueKind.INLINE_TABLE, 6_620);
    public static final ManifestField GENERATED_TEST_ADDITIONAL_PROPERTIES = field(
            "additionalProperties", ManifestValueKind.INLINE_TABLE, 6_621);
    public static final ManifestField GENERATED_TEST_CONFIG_OPTIONS = field(
            "configOptions", ManifestValueKind.INLINE_TABLE, 6_622);
    public static final ManifestField GENERATED_TEST_GLOBAL_PROPERTIES = field(
            "globalProperties", ManifestValueKind.INLINE_TABLE, 6_623);
    public static final ManifestField GENERATED_TEST_TYPE_MAPPINGS = field(
            "typeMappings", ManifestValueKind.INLINE_TABLE, 6_624);
    public static final ManifestField GENERATED_TEST_IMPORT_MAPPINGS = field(
            "importMappings", ManifestValueKind.INLINE_TABLE, 6_625);
    public static final ManifestField GENERATED_TEST_JAVA_PACKAGE = field(
            "javaPackage", ManifestValueKind.STRING, 6_626);
    public static final ManifestField GENERATED_TEST_GRPC = field(
            "grpc", ManifestValueKind.BOOLEAN, 6_627);
    public static final ManifestField GENERATED_TEST_CACHE = field(
            "cache", ManifestValueKind.STRING, 6_628);
    public static final ManifestField GENERATED_TEST_CWD = field(
            "cwd", ManifestValueKind.STRING, 6_629);
    public static final ManifestField GENERATED_TEST_ENV = field(
            "env", ManifestValueKind.INLINE_TABLE, 6_630);
    public static final ManifestField GENERATED_TEST_SECRET_ENV = field(
            "secretEnv", ManifestValueKind.INLINE_TABLE, 6_631);
    public static final ManifestField GENERATED_TEST_INHERIT_ENV = field(
            "inheritEnv", ManifestValueKind.STRING_ARRAY, 6_632);
    public static final ManifestField GENERATED_TEST_TIMEOUT_SECONDS = field(
            "timeoutSeconds", ManifestValueKind.INTEGER, 6_633);
    public static final ManifestField GENERATED_TEST_REQUIRED = field(
            "required", ManifestValueKind.BOOLEAN, 6_634);
    public static final ManifestField GENERATED_TEST_CLEAN = field(
            "clean", ManifestValueKind.BOOLEAN, 6_635);

    private FinalManifestGeneratedTestFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                GENERATED_TEST_KIND,
                GENERATED_TEST_LANGUAGE,
                GENERATED_TEST_TOOL,
                GENERATED_TEST_MAIN_CLASS,
                GENERATED_TEST_ARGS,
                GENERATED_TEST_INPUT,
                GENERATED_TEST_INPUTS,
                GENERATED_TEST_OUTPUT,
                GENERATED_TEST_PRODUCES,
                GENERATED_TEST_INTO,
                GENERATED_TEST_PRESET,
                GENERATED_TEST_GENERATOR,
                GENERATED_TEST_LIBRARY,
                GENERATED_TEST_API_PACKAGE,
                GENERATED_TEST_MODEL_PACKAGE,
                GENERATED_TEST_INVOKER_PACKAGE,
                GENERATED_TEST_CONFIG,
                GENERATED_TEST_TEMPLATE_DIR,
                GENERATED_TEST_VALIDATE_SPEC,
                GENERATED_TEST_OPTIONS,
                GENERATED_TEST_ADDITIONAL_PROPERTIES,
                GENERATED_TEST_CONFIG_OPTIONS,
                GENERATED_TEST_GLOBAL_PROPERTIES,
                GENERATED_TEST_TYPE_MAPPINGS,
                GENERATED_TEST_IMPORT_MAPPINGS,
                GENERATED_TEST_JAVA_PACKAGE,
                GENERATED_TEST_GRPC,
                GENERATED_TEST_CACHE,
                GENERATED_TEST_CWD,
                GENERATED_TEST_ENV,
                GENERATED_TEST_SECRET_ENV,
                GENERATED_TEST_INHERIT_ENV,
                GENERATED_TEST_TIMEOUT_SECONDS,
                GENERATED_TEST_REQUIRED,
                GENERATED_TEST_CLEAN);
    }

    private static ManifestField field(
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.generatedStepField(
                FinalManifestPaths.GENERATED_TEST, name, kind, canonicalOrder);
    }
}
