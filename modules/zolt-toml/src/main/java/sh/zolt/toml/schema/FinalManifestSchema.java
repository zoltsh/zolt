package sh.zolt.toml.schema;

import java.util.List;
import java.util.Set;

/** The final manifest schema catalog, populated incrementally by frozen contract domain. */
public final class FinalManifestSchema {
    private static final ManifestPath WORKSPACE = ManifestPath.of("workspace");
    private static final ManifestPath WORKSPACE_MEMBERS = WORKSPACE.child("members");
    private static final ManifestPath WORKSPACE_PROJECT = WORKSPACE.child("project");
    private static final ManifestPath PROJECT = ManifestPath.of("project");
    private static final ManifestPath PROJECT_SCM = PROJECT.child("scm");
    private static final ManifestPath PROJECT_DEVELOPER = PROJECT.child("developers").child("<id>");
    private static final ManifestPath TOOLCHAIN_ZOLT = ManifestPath.of("toolchain", "zolt");
    private static final ManifestPath TOOLCHAIN_JAVA = ManifestPath.of("toolchain", "java");
    private static final ManifestPath TOOLCHAIN_JAVA_TEST = TOOLCHAIN_JAVA.child("test");
    private static final ManifestPath VERSIONS = ManifestPath.of("versions");
    private static final ManifestPath REPOSITORIES = ManifestPath.of("repositories");
    private static final ManifestPath REPOSITORY = REPOSITORIES.child("<id>");
    private static final ManifestPath CREDENTIAL = ManifestPath.of("credentials", "<id>");
    private static final ManifestPath PLATFORMS = ManifestPath.of("platforms");
    private static final ManifestPath DEPENDENCIES = ManifestPath.of("dependencies");
    private static final ManifestPath DEPENDENCIES_API = DEPENDENCIES.child("api");
    private static final ManifestPath DEPENDENCIES_RUNTIME = DEPENDENCIES.child("runtime");
    private static final ManifestPath DEPENDENCIES_PROVIDED = DEPENDENCIES.child("provided");
    private static final ManifestPath DEPENDENCIES_DEV = DEPENDENCIES.child("dev");
    private static final ManifestPath DEPENDENCIES_TEST = DEPENDENCIES.child("test");
    private static final ManifestPath DEPENDENCIES_PROCESSOR = DEPENDENCIES.child("processor");
    private static final ManifestPath DEPENDENCIES_TEST_PROCESSOR = DEPENDENCIES.child("test-processor");
    private static final ManifestPath DEPENDENCY_CONSTRAINTS = DEPENDENCIES.child("constraints");
    private static final ManifestPath DEPENDENCY_POLICY = DEPENDENCIES.child("policy");
    private static final ManifestPath DEPENDENCY_LICENSE_POLICY = DEPENDENCY_POLICY.child("licenses");
    private static final ManifestPath DEPENDENCY_LICENSE_EXCEPTION =
            DEPENDENCIES.child("license-exceptions").child("<coordinate>");
    private static final ManifestPath BUILD = ManifestPath.of("build");
    private static final ManifestPath BUILD_OUTPUT = BUILD.child("output");
    private static final ManifestPath BUILD_METADATA = BUILD.child("metadata");
    private static final ManifestPath COMPILER = ManifestPath.of("compiler");
    private static final ManifestPath COMPILER_TEST = COMPILER.child("test");
    private static final ManifestPath COMPILER_GENERATED = COMPILER.child("generated");
    private static final ManifestPath RESOURCES = ManifestPath.of("resources");
    private static final ManifestPath RESOURCES_FILTER = RESOURCES.child("filter");
    private static final ManifestPath RESOURCES_TOKENS = RESOURCES.child("tokens");
    private static final ManifestPath GENERATED_TOOL =
            ManifestPath.of("generated", "tools", "<id>");
    private static final ManifestPath GENERATED_PRESET =
            ManifestPath.of("generated", "presets", "<id>");
    private static final ManifestPath GENERATED_MAIN =
            ManifestPath.of("generated", "main", "<id>");
    private static final ManifestPath GENERATED_TEST =
            ManifestPath.of("generated", "test", "<id>");
    private static final ManifestPath TEST_SOURCES = ManifestPath.of("test", "sources");
    private static final ManifestPath TEST_RUNTIME = ManifestPath.of("test", "runtime");
    private static final ManifestPath TEST_INTEGRATION = ManifestPath.of("test", "integration");
    private static final ManifestPath TEST_SUITE = ManifestPath.of("test", "suites", "<id>");
    private static final ManifestPath COVERAGE = ManifestPath.of("coverage");
    private static final ManifestPath PACKAGE = ManifestPath.of("package");
    private static final ManifestPath PACKAGE_MANIFEST = PACKAGE.child("manifest");
    private static final ManifestPath BOM = ManifestPath.of("bom");
    private static final ManifestPath BOM_VERSIONS = BOM.child("versions");
    private static final ManifestPath BOM_IMPORTS = BOM.child("imports");
    private static final ManifestPath FRAMEWORK_SPRING_BOOT =
            ManifestPath.of("framework", "spring-boot");
    private static final ManifestPath NATIVE = ManifestPath.of("native");
    private static final ManifestPath PUBLISH = ManifestPath.of("publish");
    private static final ManifestPath PUBLISH_REPOSITORY =
            PUBLISH.child("repositories").child("<id>");
    private static final ManifestPath PUBLISH_SIGNING = PUBLISH.child("signing");
    private static final ManifestPath PUBLISH_CENTRAL = PUBLISH.child("central");
    private static final ManifestPath TASK = ManifestPath.of("tasks", "<id>");
    private static final ManifestPath ALIASES = ManifestPath.of("aliases");

    private static final ManifestSchemaRegistry REGISTRY = createRegistry();

    private FinalManifestSchema() {
    }

    public static ManifestSchemaRegistry registry() {
        return REGISTRY;
    }

    private static ManifestSchemaRegistry createRegistry() {
        List<ManifestField> fields = fields();
        FinalManifestFieldSemantics.validateCatalog(fields);
        return new ManifestSchemaRegistry(fields, sections(), FinalManifestSymbols.registry());
    }

    private static List<ManifestSection> sections() {
        return List.of(
                section(WORKSPACE, SectionKind.SINGLETON, 1_000, Set.of("members", "project")),
                section(WORKSPACE_MEMBERS, SectionKind.SINGLETON, 1_100, Set.of()),
                section(WORKSPACE_PROJECT, SectionKind.SINGLETON, 1_200, Set.of()),
                section(PROJECT, SectionKind.SINGLETON, 2_000, Set.of("developers", "scm")),
                section(PROJECT_SCM, SectionKind.SINGLETON, 2_100, Set.of()),
                section(PROJECT_DEVELOPER, SectionKind.NAMED_ITEM, 2_200, Set.of()),
                section(TOOLCHAIN_ZOLT, SectionKind.SINGLETON, 3_000, Set.of()),
                section(TOOLCHAIN_JAVA, SectionKind.SINGLETON, 3_100, Set.of("test")),
                section(TOOLCHAIN_JAVA_TEST, SectionKind.SINGLETON, 3_200, Set.of()),
                section(VERSIONS, SectionKind.COLLECTION, 4_000, Set.of()),
                section(REPOSITORIES, SectionKind.SINGLETON, 4_100, Set.of("central", "order")),
                section(REPOSITORY, SectionKind.NAMED_ITEM, 4_200, Set.of()),
                section(CREDENTIAL, SectionKind.NAMED_ITEM, 4_300, Set.of()),
                section(PLATFORMS, SectionKind.COLLECTION, 4_400, Set.of()),
                section(
                        DEPENDENCIES,
                        SectionKind.COLLECTION,
                        5_000,
                        Set.of(
                                "api",
                                "runtime",
                                "provided",
                                "dev",
                                "test",
                                "processor",
                                "test-processor",
                                "constraints",
                                "policy",
                                "license-exceptions")),
                section(DEPENDENCIES_API, SectionKind.COLLECTION, 5_010, Set.of()),
                section(DEPENDENCIES_RUNTIME, SectionKind.COLLECTION, 5_020, Set.of()),
                section(DEPENDENCIES_PROVIDED, SectionKind.COLLECTION, 5_030, Set.of()),
                section(DEPENDENCIES_DEV, SectionKind.COLLECTION, 5_040, Set.of()),
                section(DEPENDENCIES_TEST, SectionKind.COLLECTION, 5_050, Set.of()),
                section(DEPENDENCIES_PROCESSOR, SectionKind.COLLECTION, 5_060, Set.of()),
                section(DEPENDENCIES_TEST_PROCESSOR, SectionKind.COLLECTION, 5_070, Set.of()),
                section(DEPENDENCY_CONSTRAINTS, SectionKind.COLLECTION, 5_080, Set.of()),
                section(DEPENDENCY_POLICY, SectionKind.SINGLETON, 5_090, Set.of("licenses")),
                section(DEPENDENCY_LICENSE_POLICY, SectionKind.SINGLETON, 5_100, Set.of()),
                section(DEPENDENCY_LICENSE_EXCEPTION, SectionKind.NAMED_ITEM, 5_110, Set.of()),
                section(BUILD, SectionKind.SINGLETON, 6_000, Set.of("metadata", "output")),
                section(BUILD_OUTPUT, SectionKind.SINGLETON, 6_010, Set.of()),
                section(BUILD_METADATA, SectionKind.SINGLETON, 6_020, Set.of()),
                section(COMPILER, SectionKind.SINGLETON, 6_100, Set.of("generated", "test")),
                section(COMPILER_TEST, SectionKind.SINGLETON, 6_110, Set.of()),
                section(COMPILER_GENERATED, SectionKind.SINGLETON, 6_120, Set.of()),
                section(RESOURCES, SectionKind.SINGLETON, 6_200, Set.of("filter", "tokens")),
                section(RESOURCES_FILTER, SectionKind.SINGLETON, 6_210, Set.of()),
                section(RESOURCES_TOKENS, SectionKind.COLLECTION, 6_220, Set.of()),
                section(
                        GENERATED_TOOL,
                        SectionKind.NAMED_ITEM,
                        6_300,
                        Set.of("openapi", "project", "protobuf")),
                section(GENERATED_PRESET, SectionKind.NAMED_ITEM, 6_400, Set.of()),
                section(GENERATED_MAIN, SectionKind.NAMED_ITEM, 6_500, Set.of()),
                section(GENERATED_TEST, SectionKind.NAMED_ITEM, 6_600, Set.of()),
                section(TEST_SOURCES, SectionKind.SINGLETON, 6_700, Set.of()),
                section(TEST_RUNTIME, SectionKind.SINGLETON, 6_710, Set.of()),
                section(TEST_INTEGRATION, SectionKind.SINGLETON, 6_720, Set.of()),
                section(TEST_SUITE, SectionKind.NAMED_ITEM, 6_730, Set.of("all")),
                section(COVERAGE, SectionKind.SINGLETON, 6_900, Set.of()),
                section(PACKAGE, SectionKind.SINGLETON, 7_000, Set.of("manifest")),
                section(PACKAGE_MANIFEST, SectionKind.COLLECTION, 7_010, Set.of()),
                section(BOM, SectionKind.SINGLETON, 7_100, Set.of("imports", "versions")),
                section(BOM_VERSIONS, SectionKind.COLLECTION, 7_110, Set.of()),
                section(BOM_IMPORTS, SectionKind.COLLECTION, 7_120, Set.of()),
                section(FRAMEWORK_SPRING_BOOT, SectionKind.SINGLETON, 7_200, Set.of()),
                section(NATIVE, SectionKind.SINGLETON, 7_300, Set.of()),
                section(
                        PUBLISH,
                        SectionKind.SINGLETON,
                        8_000,
                        Set.of("central", "repositories", "signing")),
                section(PUBLISH_REPOSITORY, SectionKind.NAMED_ITEM, 8_100, Set.of()),
                section(PUBLISH_SIGNING, SectionKind.SINGLETON, 8_200, Set.of()),
                section(PUBLISH_CENTRAL, SectionKind.SINGLETON, 8_300, Set.of()),
                section(
                        TASK,
                        SectionKind.NAMED_ITEM,
                        9_000,
                        FinalManifestSymbols.builtInCommandNames()),
                section(
                        ALIASES,
                        SectionKind.COLLECTION,
                        9_100,
                        FinalManifestSymbols.builtInCommandNames()));
    }

    private static List<ManifestField> fields() {
        return List.of(
                field(WORKSPACE, "name", ManifestValueKind.STRING, 1_010),
                field(WORKSPACE_MEMBERS, "default", ManifestValueKind.STRING_ARRAY, 1_110),
                field(WORKSPACE_MEMBERS, "include", ManifestValueKind.STRING_ARRAY, 1_120),
                field(WORKSPACE_MEMBERS, "exclude", ManifestValueKind.STRING_ARRAY, 1_130),
                field(WORKSPACE_PROJECT, "group", ManifestValueKind.STRING, 1_210),
                field(WORKSPACE_PROJECT, "version", ManifestValueKind.STRING, 1_220),
                field(WORKSPACE_PROJECT, "java", ManifestValueKind.INTEGER, 1_230),
                oneLineField(WORKSPACE_PROJECT, "license", ManifestValueKind.STRING_OR_INLINE_TABLE, 1_240),
                field(PROJECT, "name", ManifestValueKind.STRING, 2_010),
                field(PROJECT, "version", ManifestValueKind.STRING, 2_020),
                field(PROJECT, "group", ManifestValueKind.STRING, 2_030),
                field(PROJECT, "java", ManifestValueKind.INTEGER, 2_040),
                field(PROJECT, "main", ManifestValueKind.STRING, 2_050),
                field(PROJECT, "description", ManifestValueKind.STRING, 2_060),
                field(PROJECT, "url", ManifestValueKind.STRING, 2_070),
                field(PROJECT, "issues", ManifestValueKind.STRING, 2_080),
                oneLineField(PROJECT, "license", ManifestValueKind.STRING_OR_INLINE_TABLE, 2_090),
                field(PROJECT_SCM, "url", ManifestValueKind.STRING, 2_110),
                field(PROJECT_SCM, "connection", ManifestValueKind.STRING, 2_120),
                field(PROJECT_SCM, "developerConnection", ManifestValueKind.STRING, 2_130),
                field(PROJECT_SCM, "tag", ManifestValueKind.STRING, 2_140),
                field(PROJECT_DEVELOPER, "name", ManifestValueKind.STRING, 2_210),
                field(PROJECT_DEVELOPER, "email", ManifestValueKind.STRING, 2_220),
                field(PROJECT_DEVELOPER, "organization", ManifestValueKind.STRING, 2_230),
                field(PROJECT_DEVELOPER, "url", ManifestValueKind.STRING, 2_240),
                field(TOOLCHAIN_ZOLT, "version", ManifestValueKind.STRING, 3_010),
                field(TOOLCHAIN_JAVA, "version", ManifestValueKind.INTEGER, 3_110),
                field(TOOLCHAIN_JAVA, "distribution", ManifestValueKind.STRING, 3_120),
                field(TOOLCHAIN_JAVA, "features", ManifestValueKind.STRING_ARRAY, 3_130),
                field(TOOLCHAIN_JAVA, "policy", ManifestValueKind.STRING, 3_140),
                field(TOOLCHAIN_JAVA_TEST, "version", ManifestValueKind.INTEGER, 3_210),
                field(TOOLCHAIN_JAVA_TEST, "distribution", ManifestValueKind.STRING, 3_220),
                field(TOOLCHAIN_JAVA_TEST, "policy", ManifestValueKind.STRING, 3_230),
                mutableMapEntry(VERSIONS, "<id>", ManifestValueKind.STRING, 4_010),
                field(
                        REPOSITORIES,
                        "central",
                        ManifestValueKind.BOOLEAN_OR_STRING_OR_INLINE_TABLE,
                        4_110),
                field(REPOSITORIES, "order", ManifestValueKind.STRING_ARRAY, 4_120),
                field(REPOSITORY, "url", ManifestValueKind.STRING, 4_210),
                field(REPOSITORY, "credentials", ManifestValueKind.STRING, 4_220),
                field(CREDENTIAL, "tokenEnv", ManifestValueKind.STRING, 4_310),
                field(CREDENTIAL, "usernameEnv", ManifestValueKind.STRING, 4_320),
                field(CREDENTIAL, "passwordEnv", ManifestValueKind.STRING, 4_330),
                mutableMapEntry(
                        PLATFORMS,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        4_410),
                mutableMapEntry(
                        DEPENDENCIES,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_001),
                mutableMapEntry(
                        DEPENDENCIES_API,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_011),
                mutableMapEntry(
                        DEPENDENCIES_RUNTIME,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_021),
                mutableMapEntry(
                        DEPENDENCIES_PROVIDED,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_031),
                mutableMapEntry(
                        DEPENDENCIES_DEV,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_041),
                mutableMapEntry(
                        DEPENDENCIES_TEST,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_051),
                mutableMapEntry(
                        DEPENDENCIES_PROCESSOR,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_061),
                mutableMapEntry(
                        DEPENDENCIES_TEST_PROCESSOR,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_071),
                mutableMapEntry(
                        DEPENDENCY_CONSTRAINTS,
                        "<coordinate>",
                        ManifestValueKind.STRING_OR_INLINE_TABLE,
                        5_081),
                field(DEPENDENCY_POLICY, "conflicts", ManifestValueKind.STRING, 5_091),
                field(DEPENDENCY_POLICY, "deny", ManifestValueKind.INLINE_TABLE_ARRAY, 5_092),
                field(DEPENDENCY_LICENSE_POLICY, "allow", ManifestValueKind.STRING_ARRAY, 5_101),
                field(DEPENDENCY_LICENSE_POLICY, "deny", ManifestValueKind.STRING_ARRAY, 5_102),
                field(DEPENDENCY_LICENSE_POLICY, "unknown", ManifestValueKind.STRING, 5_103),
                field(DEPENDENCY_LICENSE_EXCEPTION, "allow", ManifestValueKind.STRING_ARRAY, 5_111),
                field(DEPENDENCY_LICENSE_EXCEPTION, "version", ManifestValueKind.STRING, 5_112),
                field(DEPENDENCY_LICENSE_EXCEPTION, "reason", ManifestValueKind.STRING, 5_113),
                field(BUILD, "sources", ManifestValueKind.STRING_ARRAY, 6_001),
                field(BUILD_OUTPUT, "root", ManifestValueKind.STRING, 6_011),
                field(BUILD_OUTPUT, "main", ManifestValueKind.STRING, 6_012),
                field(BUILD_OUTPUT, "test", ManifestValueKind.STRING, 6_013),
                field(BUILD_OUTPUT, "integration", ManifestValueKind.STRING, 6_014),
                field(BUILD_METADATA, "buildInfo", ManifestValueKind.BOOLEAN, 6_021),
                field(BUILD_METADATA, "git", ManifestValueKind.BOOLEAN, 6_022),
                field(BUILD_METADATA, "reproducible", ManifestValueKind.BOOLEAN, 6_023),
                field(COMPILER, "encoding", ManifestValueKind.STRING, 6_101),
                field(COMPILER, "jdkApi", ManifestValueKind.STRING, 6_102),
                field(COMPILER, "args", ManifestValueKind.STRING_ARRAY, 6_103),
                field(COMPILER_TEST, "jdkApi", ManifestValueKind.STRING, 6_111),
                field(COMPILER_TEST, "args", ManifestValueKind.STRING_ARRAY, 6_112),
                field(COMPILER_GENERATED, "main", ManifestValueKind.STRING, 6_121),
                field(COMPILER_GENERATED, "test", ManifestValueKind.STRING, 6_122),
                field(RESOURCES, "main", ManifestValueKind.STRING_ARRAY, 6_201),
                field(RESOURCES, "test", ManifestValueKind.STRING_ARRAY, 6_202),
                field(RESOURCES_FILTER, "targets", ManifestValueKind.STRING_ARRAY, 6_211),
                field(RESOURCES_FILTER, "include", ManifestValueKind.STRING_ARRAY, 6_212),
                field(RESOURCES_FILTER, "missing", ManifestValueKind.STRING, 6_213),
                oneLineField(RESOURCES_TOKENS, "<id>", ManifestValueKind.INLINE_TABLE, 6_221),
                field(GENERATED_TOOL, "kind", ManifestValueKind.STRING, 6_301),
                field(GENERATED_TOOL, "coordinate", ManifestValueKind.STRING, 6_302),
                field(GENERATED_TOOL, "version", ManifestValueKind.STRING, 6_303),
                field(GENERATED_TOOL, "versionRef", ManifestValueKind.STRING, 6_304),
                field(GENERATED_TOOL, "protocCoordinate", ManifestValueKind.STRING, 6_305),
                field(GENERATED_TOOL, "protocVersion", ManifestValueKind.STRING, 6_306),
                field(GENERATED_TOOL, "protocVersionRef", ManifestValueKind.STRING, 6_307),
                field(GENERATED_TOOL, "grpcCoordinate", ManifestValueKind.STRING, 6_308),
                field(GENERATED_TOOL, "grpcVersion", ManifestValueKind.STRING, 6_309),
                field(GENERATED_TOOL, "grpcVersionRef", ManifestValueKind.STRING, 6_310),
                field(GENERATED_TOOL, "coordinates", ManifestValueKind.INLINE_TABLE_ARRAY, 6_311),
                field(GENERATED_TOOL, "mainClass", ManifestValueKind.STRING, 6_312),
                field(GENERATED_TOOL, "binary", ManifestValueKind.STRING, 6_313),
                field(GENERATED_TOOL, "versionCommand", ManifestValueKind.STRING_ARRAY, 6_314),
                field(GENERATED_TOOL, "versionExpect", ManifestValueKind.STRING, 6_315),
                field(GENERATED_TOOL, "allowUnpinnedTool", ManifestValueKind.BOOLEAN, 6_316),
                field(GENERATED_PRESET, "kind", ManifestValueKind.STRING, 6_401),
                field(GENERATED_PRESET, "generator", ManifestValueKind.STRING, 6_402),
                field(GENERATED_PRESET, "library", ManifestValueKind.STRING, 6_403),
                field(GENERATED_PRESET, "apiPackage", ManifestValueKind.STRING, 6_404),
                field(GENERATED_PRESET, "modelPackage", ManifestValueKind.STRING, 6_405),
                field(GENERATED_PRESET, "invokerPackage", ManifestValueKind.STRING, 6_406),
                field(GENERATED_PRESET, "config", ManifestValueKind.STRING, 6_407),
                field(GENERATED_PRESET, "templateDir", ManifestValueKind.STRING, 6_408),
                field(GENERATED_PRESET, "validateSpec", ManifestValueKind.BOOLEAN, 6_409),
                field(GENERATED_PRESET, "options", ManifestValueKind.INLINE_TABLE, 6_410),
                field(GENERATED_PRESET, "additionalProperties", ManifestValueKind.INLINE_TABLE, 6_411),
                field(GENERATED_PRESET, "configOptions", ManifestValueKind.INLINE_TABLE, 6_412),
                field(GENERATED_PRESET, "globalProperties", ManifestValueKind.INLINE_TABLE, 6_413),
                field(GENERATED_PRESET, "typeMappings", ManifestValueKind.INLINE_TABLE, 6_414),
                field(GENERATED_PRESET, "importMappings", ManifestValueKind.INLINE_TABLE, 6_415),
                generatedStepField(GENERATED_MAIN, "kind", ManifestValueKind.STRING, 6_501),
                generatedStepField(GENERATED_MAIN, "language", ManifestValueKind.STRING, 6_502),
                generatedStepField(GENERATED_MAIN, "tool", ManifestValueKind.STRING, 6_503),
                generatedStepField(GENERATED_MAIN, "mainClass", ManifestValueKind.STRING, 6_504),
                generatedStepField(GENERATED_MAIN, "args", ManifestValueKind.STRING_ARRAY, 6_505),
                generatedStepField(GENERATED_MAIN, "input", ManifestValueKind.STRING, 6_506),
                generatedStepField(GENERATED_MAIN, "inputs", ManifestValueKind.STRING_ARRAY, 6_507),
                generatedStepField(GENERATED_MAIN, "output", ManifestValueKind.STRING, 6_508),
                generatedStepField(GENERATED_MAIN, "produces", ManifestValueKind.STRING, 6_509),
                generatedStepField(GENERATED_MAIN, "into", ManifestValueKind.STRING, 6_510),
                generatedStepField(GENERATED_MAIN, "preset", ManifestValueKind.STRING, 6_511),
                generatedStepField(GENERATED_MAIN, "generator", ManifestValueKind.STRING, 6_512),
                generatedStepField(GENERATED_MAIN, "library", ManifestValueKind.STRING, 6_513),
                generatedStepField(GENERATED_MAIN, "apiPackage", ManifestValueKind.STRING, 6_514),
                generatedStepField(GENERATED_MAIN, "modelPackage", ManifestValueKind.STRING, 6_515),
                generatedStepField(GENERATED_MAIN, "invokerPackage", ManifestValueKind.STRING, 6_516),
                generatedStepField(GENERATED_MAIN, "config", ManifestValueKind.STRING, 6_517),
                generatedStepField(GENERATED_MAIN, "templateDir", ManifestValueKind.STRING, 6_518),
                generatedStepField(GENERATED_MAIN, "validateSpec", ManifestValueKind.BOOLEAN, 6_519),
                generatedStepField(GENERATED_MAIN, "options", ManifestValueKind.INLINE_TABLE, 6_520),
                generatedStepField(GENERATED_MAIN, "additionalProperties", ManifestValueKind.INLINE_TABLE, 6_521),
                generatedStepField(GENERATED_MAIN, "configOptions", ManifestValueKind.INLINE_TABLE, 6_522),
                generatedStepField(GENERATED_MAIN, "globalProperties", ManifestValueKind.INLINE_TABLE, 6_523),
                generatedStepField(GENERATED_MAIN, "typeMappings", ManifestValueKind.INLINE_TABLE, 6_524),
                generatedStepField(GENERATED_MAIN, "importMappings", ManifestValueKind.INLINE_TABLE, 6_525),
                generatedStepField(GENERATED_MAIN, "javaPackage", ManifestValueKind.STRING, 6_526),
                generatedStepField(GENERATED_MAIN, "grpc", ManifestValueKind.BOOLEAN, 6_527),
                generatedStepField(GENERATED_MAIN, "cache", ManifestValueKind.STRING, 6_528),
                generatedStepField(GENERATED_MAIN, "cwd", ManifestValueKind.STRING, 6_529),
                generatedStepField(GENERATED_MAIN, "env", ManifestValueKind.INLINE_TABLE, 6_530),
                generatedStepField(GENERATED_MAIN, "secretEnv", ManifestValueKind.INLINE_TABLE, 6_531),
                generatedStepField(GENERATED_MAIN, "inheritEnv", ManifestValueKind.STRING_ARRAY, 6_532),
                generatedStepField(GENERATED_MAIN, "timeoutSeconds", ManifestValueKind.INTEGER, 6_533),
                generatedStepField(GENERATED_MAIN, "required", ManifestValueKind.BOOLEAN, 6_534),
                generatedStepField(GENERATED_MAIN, "clean", ManifestValueKind.BOOLEAN, 6_535),
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
                field(ALIASES, "<id>", ManifestValueKind.STRING_ARRAY, 9_101));
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        ManifestPath path = section.child(name);
        FinalManifestFieldSemantics.Metadata semantics = FinalManifestFieldSemantics.field(path);
        return new ManifestField(
                path,
                kind,
                FormattingPolicy.DEFAULT,
                MutationPolicy.NONE,
                canonicalOrder,
                semantics.symbolFamily(),
                semantics.validation(),
                FinalManifestFieldSemantics.dynamicKeys(path));
    }

    private static ManifestField oneLineField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        ManifestPath path = section.child(name);
        FinalManifestFieldSemantics.Metadata semantics = FinalManifestFieldSemantics.field(path);
        return new ManifestField(
                path,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.NONE,
                canonicalOrder,
                semantics.symbolFamily(),
                semantics.validation(),
                FinalManifestFieldSemantics.dynamicKeys(path));
    }

    private static ManifestField mutableMapEntry(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        ManifestPath path = section.child(name);
        FinalManifestFieldSemantics.Metadata semantics = FinalManifestFieldSemantics.field(path);
        return new ManifestField(
                path,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.REPLACE_ENTRY,
                canonicalOrder,
                semantics.symbolFamily(),
                semantics.validation(),
                FinalManifestFieldSemantics.dynamicKeys(path));
    }

    private static ManifestField generatedStepField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return field(section, name, kind, canonicalOrder);
    }

    private static ManifestSection section(
            ManifestPath path,
            SectionKind kind,
            int canonicalOrder,
            Set<String> reservedChildren) {
        return new ManifestSection(
                path,
                kind,
                canonicalOrder,
                reservedChildren,
                FinalManifestFieldSemantics.dynamicKeys(path));
    }
}
