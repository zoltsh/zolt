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
    private static final ManifestPath TEST_SOURCES = ManifestPath.of("test", "sources");
    private static final ManifestPath TEST_RUNTIME = ManifestPath.of("test", "runtime");
    private static final ManifestPath TEST_INTEGRATION = ManifestPath.of("test", "integration");
    private static final ManifestPath TEST_SUITE = ManifestPath.of("test", "suites", "<id>");
    private static final ManifestPath COVERAGE = ManifestPath.of("coverage");

    private static final ManifestSchemaRegistry REGISTRY =
            new ManifestSchemaRegistry(fields(), sections(), FinalManifestSymbols.registry());

    private FinalManifestSchema() {
    }

    public static ManifestSchemaRegistry registry() {
        return REGISTRY;
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
                section(TEST_SOURCES, SectionKind.SINGLETON, 6_700, Set.of()),
                section(TEST_RUNTIME, SectionKind.SINGLETON, 6_710, Set.of()),
                section(TEST_INTEGRATION, SectionKind.SINGLETON, 6_720, Set.of()),
                section(TEST_SUITE, SectionKind.NAMED_ITEM, 6_730, Set.of("all")),
                section(COVERAGE, SectionKind.SINGLETON, 6_900, Set.of()));
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
                field(COVERAGE, "method", ManifestValueKind.NUMBER, 6_940));
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return new ManifestField(
                section.child(name),
                kind,
                FormattingPolicy.DEFAULT,
                MutationPolicy.NONE,
                canonicalOrder);
    }

    private static ManifestField oneLineField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return new ManifestField(
                section.child(name),
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.NONE,
                canonicalOrder);
    }

    private static ManifestField mutableMapEntry(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return new ManifestField(
                section.child(name),
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.REPLACE_ENTRY,
                canonicalOrder);
    }

    private static ManifestSection section(
            ManifestPath path,
            SectionKind kind,
            int canonicalOrder,
            Set<String> reservedChildren) {
        return new ManifestSection(path, kind, canonicalOrder, reservedChildren);
    }
}
