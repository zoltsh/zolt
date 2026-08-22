package sh.zolt.toml.schema;

import java.util.List;
import java.util.Set;

/** Section descriptors for the final manifest schema. */
final class FinalManifestSections {
    private FinalManifestSections() {
    }

    static List<ManifestSection> sections() {
        return List.of(
                section(FinalManifestPaths.WORKSPACE, SectionKind.SINGLETON, 1_000, Set.of("members", "project")),
                section(FinalManifestPaths.WORKSPACE_MEMBERS, SectionKind.SINGLETON, 1_100, Set.of()),
                section(FinalManifestPaths.WORKSPACE_PROJECT, SectionKind.SINGLETON, 1_200, Set.of()),
                section(FinalManifestPaths.PROJECT, SectionKind.SINGLETON, 2_000, Set.of("developers", "scm")),
                section(FinalManifestPaths.PROJECT_SCM, SectionKind.SINGLETON, 2_100, Set.of()),
                section(FinalManifestPaths.PROJECT_DEVELOPERS, SectionKind.COLLECTION, 2_190, Set.of()),
                section(FinalManifestPaths.PROJECT_DEVELOPER, SectionKind.NAMED_ITEM, 2_200, Set.of()),
                section(FinalManifestPaths.TOOLCHAIN_ZOLT, SectionKind.SINGLETON, 3_000, Set.of()),
                section(FinalManifestPaths.TOOLCHAIN_JAVA, SectionKind.SINGLETON, 3_100, Set.of("test")),
                section(FinalManifestPaths.TOOLCHAIN_JAVA_TEST, SectionKind.SINGLETON, 3_200, Set.of()),
                section(FinalManifestPaths.VERSIONS, SectionKind.COLLECTION, 4_000, Set.of()),
                section(FinalManifestPaths.REPOSITORIES, SectionKind.SINGLETON, 4_100, Set.of("central", "order")),
                section(FinalManifestPaths.REPOSITORY, SectionKind.NAMED_ITEM, 4_200, Set.of()),
                section(FinalManifestPaths.CREDENTIALS, SectionKind.COLLECTION, 4_290, Set.of()),
                section(FinalManifestPaths.CREDENTIAL, SectionKind.NAMED_ITEM, 4_300, Set.of()),
                section(FinalManifestPaths.PLATFORMS, SectionKind.COLLECTION, 4_400, Set.of()),
                section(
                        FinalManifestPaths.DEPENDENCIES,
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
                section(FinalManifestPaths.DEPENDENCIES_API, SectionKind.COLLECTION, 5_010, Set.of()),
                section(FinalManifestPaths.DEPENDENCIES_RUNTIME, SectionKind.COLLECTION, 5_020, Set.of()),
                section(FinalManifestPaths.DEPENDENCIES_PROVIDED, SectionKind.COLLECTION, 5_030, Set.of()),
                section(FinalManifestPaths.DEPENDENCIES_DEV, SectionKind.COLLECTION, 5_040, Set.of()),
                section(FinalManifestPaths.DEPENDENCIES_TEST, SectionKind.COLLECTION, 5_050, Set.of()),
                section(FinalManifestPaths.DEPENDENCIES_PROCESSOR, SectionKind.COLLECTION, 5_060, Set.of()),
                section(FinalManifestPaths.DEPENDENCIES_TEST_PROCESSOR, SectionKind.COLLECTION, 5_070, Set.of()),
                section(FinalManifestPaths.DEPENDENCY_CONSTRAINTS, SectionKind.COLLECTION, 5_080, Set.of()),
                section(FinalManifestPaths.DEPENDENCY_POLICY, SectionKind.SINGLETON, 5_090, Set.of("licenses")),
                section(FinalManifestPaths.DEPENDENCY_LICENSE_POLICY, SectionKind.SINGLETON, 5_100, Set.of()),
                section(FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTIONS, SectionKind.COLLECTION, 5_105, Set.of()),
                section(FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION, SectionKind.NAMED_ITEM, 5_110, Set.of()),
                section(FinalManifestPaths.BUILD, SectionKind.SINGLETON, 6_000, Set.of("metadata", "output")),
                section(FinalManifestPaths.BUILD_OUTPUT, SectionKind.SINGLETON, 6_010, Set.of()),
                section(FinalManifestPaths.BUILD_METADATA, SectionKind.SINGLETON, 6_020, Set.of()),
                section(FinalManifestPaths.COMPILER, SectionKind.SINGLETON, 6_100, Set.of("generated", "test")),
                section(FinalManifestPaths.COMPILER_TEST, SectionKind.SINGLETON, 6_110, Set.of()),
                section(FinalManifestPaths.COMPILER_GENERATED, SectionKind.SINGLETON, 6_120, Set.of()),
                section(FinalManifestPaths.RESOURCES, SectionKind.SINGLETON, 6_200, Set.of("filter", "tokens")),
                section(FinalManifestPaths.RESOURCES_FILTER, SectionKind.SINGLETON, 6_210, Set.of()),
                section(FinalManifestPaths.RESOURCES_TOKENS, SectionKind.COLLECTION, 6_220, Set.of()),
                section(FinalManifestPaths.GENERATED_TOOLS, SectionKind.COLLECTION, 6_290, Set.of()),
                section(
                        FinalManifestPaths.GENERATED_TOOL,
                        SectionKind.NAMED_ITEM,
                        6_300,
                        Set.of("project")),
                section(FinalManifestPaths.GENERATED_PRESETS, SectionKind.COLLECTION, 6_390, Set.of()),
                section(FinalManifestPaths.GENERATED_PRESET, SectionKind.NAMED_ITEM, 6_400, Set.of()),
                section(FinalManifestPaths.GENERATED_MAIN_STEPS, SectionKind.COLLECTION, 6_490, Set.of()),
                section(FinalManifestPaths.GENERATED_MAIN, SectionKind.NAMED_ITEM, 6_500, Set.of()),
                section(FinalManifestPaths.GENERATED_TEST_STEPS, SectionKind.COLLECTION, 6_590, Set.of()),
                section(FinalManifestPaths.GENERATED_TEST, SectionKind.NAMED_ITEM, 6_600, Set.of()),
                section(FinalManifestPaths.TEST_SOURCES, SectionKind.SINGLETON, 6_700, Set.of()),
                section(FinalManifestPaths.TEST_RUNTIME, SectionKind.SINGLETON, 6_710, Set.of()),
                section(FinalManifestPaths.TEST_INTEGRATION, SectionKind.SINGLETON, 6_720, Set.of()),
                section(FinalManifestPaths.TEST_SUITES, SectionKind.COLLECTION, 6_725, Set.of()),
                section(FinalManifestPaths.TEST_SUITE, SectionKind.NAMED_ITEM, 6_730, Set.of("all")),
                section(FinalManifestPaths.COVERAGE, SectionKind.SINGLETON, 6_900, Set.of()),
                section(FinalManifestPaths.PACKAGE, SectionKind.SINGLETON, 7_000, Set.of("manifest")),
                section(FinalManifestPaths.PACKAGE_MANIFEST, SectionKind.COLLECTION, 7_010, Set.of()),
                section(FinalManifestPaths.BOM, SectionKind.SINGLETON, 7_100, Set.of("imports", "versions")),
                section(FinalManifestPaths.BOM_VERSIONS, SectionKind.COLLECTION, 7_110, Set.of()),
                section(FinalManifestPaths.BOM_IMPORTS, SectionKind.COLLECTION, 7_120, Set.of()),
                section(FinalManifestPaths.FRAMEWORK_SPRING_BOOT, SectionKind.SINGLETON, 7_200, Set.of()),
                section(FinalManifestPaths.NATIVE, SectionKind.SINGLETON, 7_300, Set.of()),
                section(
                        FinalManifestPaths.PUBLISH,
                        SectionKind.SINGLETON,
                        8_000,
                        Set.of("central", "repositories", "signing")),
                section(FinalManifestPaths.PUBLISH_REPOSITORIES, SectionKind.COLLECTION, 8_090, Set.of()),
                section(FinalManifestPaths.PUBLISH_REPOSITORY, SectionKind.NAMED_ITEM, 8_100, Set.of()),
                section(FinalManifestPaths.PUBLISH_SIGNING, SectionKind.SINGLETON, 8_200, Set.of()),
                section(FinalManifestPaths.PUBLISH_CENTRAL, SectionKind.SINGLETON, 8_300, Set.of()),
                section(FinalManifestPaths.TASKS, SectionKind.COLLECTION, 8_990, Set.of()),
                section(
                        FinalManifestPaths.TASK,
                        SectionKind.NAMED_ITEM,
                        9_000,
                        FinalManifestSymbols.builtInCommandNames()),
                section(
                        FinalManifestPaths.ALIASES,
                        SectionKind.COLLECTION,
                        9_100,
                        FinalManifestSymbols.builtInCommandNames()));
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
