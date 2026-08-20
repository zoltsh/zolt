package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored main generated-source steps. */
public final class FinalManifestGeneratedMainFields {
    public static final ManifestField GENERATED_MAIN_KIND = field(
            "kind", ManifestValueKind.STRING, 6_501);
    public static final ManifestField GENERATED_MAIN_LANGUAGE = field(
            "language", ManifestValueKind.STRING, 6_502);
    public static final ManifestField GENERATED_MAIN_TOOL = field(
            "tool", ManifestValueKind.STRING, 6_503);
    public static final ManifestField GENERATED_MAIN_MAIN_CLASS = field(
            "mainClass", ManifestValueKind.STRING, 6_504);
    public static final ManifestField GENERATED_MAIN_ARGS = field(
            "args", ManifestValueKind.STRING_ARRAY, 6_505);
    public static final ManifestField GENERATED_MAIN_INPUT = field(
            "input", ManifestValueKind.STRING, 6_506);
    public static final ManifestField GENERATED_MAIN_INPUTS = field(
            "inputs", ManifestValueKind.STRING_ARRAY, 6_507);
    public static final ManifestField GENERATED_MAIN_OUTPUT = field(
            "output", ManifestValueKind.STRING, 6_508);
    public static final ManifestField GENERATED_MAIN_PRODUCES = field(
            "produces", ManifestValueKind.STRING, 6_509);
    public static final ManifestField GENERATED_MAIN_INTO = field(
            "into", ManifestValueKind.STRING, 6_510);
    public static final ManifestField GENERATED_MAIN_PRESET = field(
            "preset", ManifestValueKind.STRING, 6_511);
    public static final ManifestField GENERATED_MAIN_GENERATOR = field(
            "generator", ManifestValueKind.STRING, 6_512);
    public static final ManifestField GENERATED_MAIN_LIBRARY = field(
            "library", ManifestValueKind.STRING, 6_513);
    public static final ManifestField GENERATED_MAIN_API_PACKAGE = field(
            "apiPackage", ManifestValueKind.STRING, 6_514);
    public static final ManifestField GENERATED_MAIN_MODEL_PACKAGE = field(
            "modelPackage", ManifestValueKind.STRING, 6_515);
    public static final ManifestField GENERATED_MAIN_INVOKER_PACKAGE = field(
            "invokerPackage", ManifestValueKind.STRING, 6_516);
    public static final ManifestField GENERATED_MAIN_CONFIG = field(
            "config", ManifestValueKind.STRING, 6_517);
    public static final ManifestField GENERATED_MAIN_TEMPLATE_DIR = field(
            "templateDir", ManifestValueKind.STRING, 6_518);
    public static final ManifestField GENERATED_MAIN_VALIDATE_SPEC = field(
            "validateSpec", ManifestValueKind.BOOLEAN, 6_519);
    public static final ManifestField GENERATED_MAIN_OPTIONS = field(
            "options", ManifestValueKind.INLINE_TABLE, 6_520);
    public static final ManifestField GENERATED_MAIN_ADDITIONAL_PROPERTIES = field(
            "additionalProperties", ManifestValueKind.INLINE_TABLE, 6_521);
    public static final ManifestField GENERATED_MAIN_CONFIG_OPTIONS = field(
            "configOptions", ManifestValueKind.INLINE_TABLE, 6_522);
    public static final ManifestField GENERATED_MAIN_GLOBAL_PROPERTIES = field(
            "globalProperties", ManifestValueKind.INLINE_TABLE, 6_523);
    public static final ManifestField GENERATED_MAIN_TYPE_MAPPINGS = field(
            "typeMappings", ManifestValueKind.INLINE_TABLE, 6_524);
    public static final ManifestField GENERATED_MAIN_IMPORT_MAPPINGS = field(
            "importMappings", ManifestValueKind.INLINE_TABLE, 6_525);
    public static final ManifestField GENERATED_MAIN_JAVA_PACKAGE = field(
            "javaPackage", ManifestValueKind.STRING, 6_526);
    public static final ManifestField GENERATED_MAIN_GRPC = field(
            "grpc", ManifestValueKind.BOOLEAN, 6_527);
    public static final ManifestField GENERATED_MAIN_CACHE = field(
            "cache", ManifestValueKind.STRING, 6_528);
    public static final ManifestField GENERATED_MAIN_CWD = field(
            "cwd", ManifestValueKind.STRING, 6_529);
    public static final ManifestField GENERATED_MAIN_ENV = field(
            "env", ManifestValueKind.INLINE_TABLE, 6_530);
    public static final ManifestField GENERATED_MAIN_SECRET_ENV = field(
            "secretEnv", ManifestValueKind.INLINE_TABLE, 6_531);
    public static final ManifestField GENERATED_MAIN_INHERIT_ENV = field(
            "inheritEnv", ManifestValueKind.STRING_ARRAY, 6_532);
    public static final ManifestField GENERATED_MAIN_TIMEOUT_SECONDS = field(
            "timeoutSeconds", ManifestValueKind.INTEGER, 6_533);
    public static final ManifestField GENERATED_MAIN_REQUIRED = field(
            "required", ManifestValueKind.BOOLEAN, 6_534);
    public static final ManifestField GENERATED_MAIN_CLEAN = field(
            "clean", ManifestValueKind.BOOLEAN, 6_535);

    private FinalManifestGeneratedMainFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                GENERATED_MAIN_KIND,
                GENERATED_MAIN_LANGUAGE,
                GENERATED_MAIN_TOOL,
                GENERATED_MAIN_MAIN_CLASS,
                GENERATED_MAIN_ARGS,
                GENERATED_MAIN_INPUT,
                GENERATED_MAIN_INPUTS,
                GENERATED_MAIN_OUTPUT,
                GENERATED_MAIN_PRODUCES,
                GENERATED_MAIN_INTO,
                GENERATED_MAIN_PRESET,
                GENERATED_MAIN_GENERATOR,
                GENERATED_MAIN_LIBRARY,
                GENERATED_MAIN_API_PACKAGE,
                GENERATED_MAIN_MODEL_PACKAGE,
                GENERATED_MAIN_INVOKER_PACKAGE,
                GENERATED_MAIN_CONFIG,
                GENERATED_MAIN_TEMPLATE_DIR,
                GENERATED_MAIN_VALIDATE_SPEC,
                GENERATED_MAIN_OPTIONS,
                GENERATED_MAIN_ADDITIONAL_PROPERTIES,
                GENERATED_MAIN_CONFIG_OPTIONS,
                GENERATED_MAIN_GLOBAL_PROPERTIES,
                GENERATED_MAIN_TYPE_MAPPINGS,
                GENERATED_MAIN_IMPORT_MAPPINGS,
                GENERATED_MAIN_JAVA_PACKAGE,
                GENERATED_MAIN_GRPC,
                GENERATED_MAIN_CACHE,
                GENERATED_MAIN_CWD,
                GENERATED_MAIN_ENV,
                GENERATED_MAIN_SECRET_ENV,
                GENERATED_MAIN_INHERIT_ENV,
                GENERATED_MAIN_TIMEOUT_SECONDS,
                GENERATED_MAIN_REQUIRED,
                GENERATED_MAIN_CLEAN);
    }

    private static ManifestField field(
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.generatedStepField(
                FinalManifestPaths.GENERATED_MAIN, name, kind, canonicalOrder);
    }
}
