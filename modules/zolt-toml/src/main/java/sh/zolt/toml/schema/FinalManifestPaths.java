package sh.zolt.toml.schema;

/** Shared structural handles used by final schema catalogs and semantic decoders. */
public final class FinalManifestPaths {
    public static final ManifestPath WORKSPACE = ManifestPath.of("workspace");
    public static final ManifestPath WORKSPACE_MEMBERS = WORKSPACE.child("members");
    public static final ManifestPath WORKSPACE_PROJECT = WORKSPACE.child("project");
    public static final ManifestPath PROJECT = ManifestPath.of("project");
    public static final ManifestPath PROJECT_SCM = PROJECT.child("scm");
    public static final ManifestPath PROJECT_DEVELOPERS = PROJECT.child("developers");
    public static final ManifestPath PROJECT_DEVELOPER = PROJECT_DEVELOPERS.child("<id>");
    public static final ManifestPath TOOLCHAIN_ZOLT = ManifestPath.of("toolchain", "zolt");
    public static final ManifestPath TOOLCHAIN_JAVA = ManifestPath.of("toolchain", "java");
    public static final ManifestPath TOOLCHAIN_JAVA_TEST = TOOLCHAIN_JAVA.child("test");
    public static final ManifestPath VERSIONS = ManifestPath.of("versions");
    public static final ManifestPath REPOSITORIES = ManifestPath.of("repositories");
    public static final ManifestPath REPOSITORY = REPOSITORIES.child("<id>");
    public static final ManifestPath CREDENTIALS = ManifestPath.of("credentials");
    public static final ManifestPath CREDENTIAL = CREDENTIALS.child("<id>");
    public static final ManifestPath PLATFORMS = ManifestPath.of("platforms");
    public static final ManifestPath DEPENDENCIES = ManifestPath.of("dependencies");
    public static final ManifestPath DEPENDENCIES_API = DEPENDENCIES.child("api");
    public static final ManifestPath DEPENDENCIES_RUNTIME = DEPENDENCIES.child("runtime");
    public static final ManifestPath DEPENDENCIES_PROVIDED = DEPENDENCIES.child("provided");
    public static final ManifestPath DEPENDENCIES_DEV = DEPENDENCIES.child("dev");
    public static final ManifestPath DEPENDENCIES_TEST = DEPENDENCIES.child("test");
    public static final ManifestPath DEPENDENCIES_PROCESSOR = DEPENDENCIES.child("processor");
    public static final ManifestPath DEPENDENCIES_TEST_PROCESSOR = DEPENDENCIES.child("test-processor");
    public static final ManifestPath DEPENDENCY_CONSTRAINTS = DEPENDENCIES.child("constraints");
    public static final ManifestPath DEPENDENCY_POLICY = DEPENDENCIES.child("policy");
    public static final ManifestPath DEPENDENCY_LICENSE_POLICY = DEPENDENCY_POLICY.child("licenses");
    public static final ManifestPath DEPENDENCY_LICENSE_EXCEPTIONS = DEPENDENCIES.child("license-exceptions");
    public static final ManifestPath DEPENDENCY_LICENSE_EXCEPTION =
            DEPENDENCY_LICENSE_EXCEPTIONS.child("<coordinate>");
    public static final ManifestPath BUILD = ManifestPath.of("build");
    public static final ManifestPath BUILD_OUTPUT = BUILD.child("output");
    public static final ManifestPath BUILD_METADATA = BUILD.child("metadata");
    public static final ManifestPath COMPILER = ManifestPath.of("compiler");
    public static final ManifestPath COMPILER_TEST = COMPILER.child("test");
    public static final ManifestPath COMPILER_GENERATED = COMPILER.child("generated");
    public static final ManifestPath RESOURCES = ManifestPath.of("resources");
    public static final ManifestPath RESOURCES_FILTER = RESOURCES.child("filter");
    public static final ManifestPath RESOURCES_TOKENS = RESOURCES.child("tokens");
    public static final ManifestPath GENERATED_TOOLS = ManifestPath.of("generated", "tools");
    public static final ManifestPath GENERATED_TOOL = GENERATED_TOOLS.child("<id>");
    public static final ManifestPath GENERATED_PRESETS = ManifestPath.of("generated", "presets");
    public static final ManifestPath GENERATED_PRESET = GENERATED_PRESETS.child("<id>");
    static final ManifestPath GENERATED_MAIN_STEPS = ManifestPath.of("generated", "main");
    static final ManifestPath GENERATED_MAIN = GENERATED_MAIN_STEPS.child("<id>");
    static final ManifestPath GENERATED_TEST_STEPS = ManifestPath.of("generated", "test");
    static final ManifestPath GENERATED_TEST = GENERATED_TEST_STEPS.child("<id>");
    static final ManifestPath TEST_SOURCES = ManifestPath.of("test", "sources");
    static final ManifestPath TEST_RUNTIME = ManifestPath.of("test", "runtime");
    static final ManifestPath TEST_INTEGRATION = ManifestPath.of("test", "integration");
    static final ManifestPath TEST_SUITES = ManifestPath.of("test", "suites");
    static final ManifestPath TEST_SUITE = TEST_SUITES.child("<id>");
    static final ManifestPath COVERAGE = ManifestPath.of("coverage");
    static final ManifestPath PACKAGE = ManifestPath.of("package");
    static final ManifestPath PACKAGE_MANIFEST = PACKAGE.child("manifest");
    static final ManifestPath BOM = ManifestPath.of("bom");
    static final ManifestPath BOM_VERSIONS = BOM.child("versions");
    static final ManifestPath BOM_IMPORTS = BOM.child("imports");
    static final ManifestPath FRAMEWORK_SPRING_BOOT = ManifestPath.of("framework", "spring-boot");
    static final ManifestPath NATIVE = ManifestPath.of("native");
    static final ManifestPath PUBLISH = ManifestPath.of("publish");
    static final ManifestPath PUBLISH_REPOSITORIES = PUBLISH.child("repositories");
    static final ManifestPath PUBLISH_REPOSITORY = PUBLISH_REPOSITORIES.child("<id>");
    static final ManifestPath PUBLISH_SIGNING = PUBLISH.child("signing");
    static final ManifestPath PUBLISH_CENTRAL = PUBLISH.child("central");
    static final ManifestPath TASKS = ManifestPath.of("tasks");
    static final ManifestPath TASK = TASKS.child("<id>");
    static final ManifestPath ALIASES = ManifestPath.of("aliases");

    private FinalManifestPaths() {
    }
}
