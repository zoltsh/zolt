package sh.zolt.toml.schema;

import static sh.zolt.toml.schema.FinalManifestPaths.ALIASES;
import static sh.zolt.toml.schema.FinalManifestPaths.BOM;
import static sh.zolt.toml.schema.FinalManifestPaths.BOM_IMPORTS;
import static sh.zolt.toml.schema.FinalManifestPaths.BOM_VERSIONS;
import static sh.zolt.toml.schema.FinalManifestPaths.BUILD;
import static sh.zolt.toml.schema.FinalManifestPaths.BUILD_METADATA;
import static sh.zolt.toml.schema.FinalManifestPaths.BUILD_OUTPUT;
import static sh.zolt.toml.schema.FinalManifestPaths.COMPILER;
import static sh.zolt.toml.schema.FinalManifestPaths.COMPILER_GENERATED;
import static sh.zolt.toml.schema.FinalManifestPaths.COMPILER_TEST;
import static sh.zolt.toml.schema.FinalManifestPaths.COVERAGE;
import static sh.zolt.toml.schema.FinalManifestPaths.CREDENTIAL;
import static sh.zolt.toml.schema.FinalManifestPaths.CREDENTIALS;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES_API;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES_DEV;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES_PROCESSOR;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES_PROVIDED;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES_RUNTIME;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES_TEST;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCIES_TEST_PROCESSOR;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCY_CONSTRAINTS;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTIONS;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCY_LICENSE_POLICY;
import static sh.zolt.toml.schema.FinalManifestPaths.DEPENDENCY_POLICY;
import static sh.zolt.toml.schema.FinalManifestPaths.FRAMEWORK_SPRING_BOOT;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_MAIN;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_MAIN_STEPS;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_PRESET;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_PRESETS;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_TEST;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_TEST_STEPS;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_TOOL;
import static sh.zolt.toml.schema.FinalManifestPaths.GENERATED_TOOLS;
import static sh.zolt.toml.schema.FinalManifestPaths.NATIVE;
import static sh.zolt.toml.schema.FinalManifestPaths.PACKAGE;
import static sh.zolt.toml.schema.FinalManifestPaths.PACKAGE_MANIFEST;
import static sh.zolt.toml.schema.FinalManifestPaths.PLATFORMS;
import static sh.zolt.toml.schema.FinalManifestPaths.PROJECT;
import static sh.zolt.toml.schema.FinalManifestPaths.PROJECT_DEVELOPER;
import static sh.zolt.toml.schema.FinalManifestPaths.PROJECT_DEVELOPERS;
import static sh.zolt.toml.schema.FinalManifestPaths.PROJECT_SCM;
import static sh.zolt.toml.schema.FinalManifestPaths.PUBLISH;
import static sh.zolt.toml.schema.FinalManifestPaths.PUBLISH_CENTRAL;
import static sh.zolt.toml.schema.FinalManifestPaths.PUBLISH_REPOSITORIES;
import static sh.zolt.toml.schema.FinalManifestPaths.PUBLISH_REPOSITORY;
import static sh.zolt.toml.schema.FinalManifestPaths.PUBLISH_SIGNING;
import static sh.zolt.toml.schema.FinalManifestPaths.REPOSITORIES;
import static sh.zolt.toml.schema.FinalManifestPaths.REPOSITORY;
import static sh.zolt.toml.schema.FinalManifestPaths.RESOURCES;
import static sh.zolt.toml.schema.FinalManifestPaths.RESOURCES_FILTER;
import static sh.zolt.toml.schema.FinalManifestPaths.RESOURCES_TOKENS;
import static sh.zolt.toml.schema.FinalManifestPaths.TASK;
import static sh.zolt.toml.schema.FinalManifestPaths.TASKS;
import static sh.zolt.toml.schema.FinalManifestPaths.TEST_INTEGRATION;
import static sh.zolt.toml.schema.FinalManifestPaths.TEST_RUNTIME;
import static sh.zolt.toml.schema.FinalManifestPaths.TEST_SOURCES;
import static sh.zolt.toml.schema.FinalManifestPaths.TEST_SUITE;
import static sh.zolt.toml.schema.FinalManifestPaths.TEST_SUITES;
import static sh.zolt.toml.schema.FinalManifestPaths.TOOLCHAIN_JAVA;
import static sh.zolt.toml.schema.FinalManifestPaths.TOOLCHAIN_JAVA_TEST;
import static sh.zolt.toml.schema.FinalManifestPaths.TOOLCHAIN_ZOLT;
import static sh.zolt.toml.schema.FinalManifestPaths.VERSIONS;
import static sh.zolt.toml.schema.FinalManifestPaths.WORKSPACE;
import static sh.zolt.toml.schema.FinalManifestPaths.WORKSPACE_MEMBERS;
import static sh.zolt.toml.schema.FinalManifestPaths.WORKSPACE_PROJECT;

import java.util.List;
import java.util.Set;

/** Section descriptors for the final manifest schema. */
final class FinalManifestSections {
    private FinalManifestSections() {
    }

    static List<ManifestSection> sections() {
        return List.of(
                section(WORKSPACE, SectionKind.SINGLETON, 1_000, Set.of("members", "project")),
                section(WORKSPACE_MEMBERS, SectionKind.SINGLETON, 1_100, Set.of()),
                section(WORKSPACE_PROJECT, SectionKind.SINGLETON, 1_200, Set.of()),
                section(PROJECT, SectionKind.SINGLETON, 2_000, Set.of("developers", "scm")),
                section(PROJECT_SCM, SectionKind.SINGLETON, 2_100, Set.of()),
                section(PROJECT_DEVELOPERS, SectionKind.COLLECTION, 2_190, Set.of()),
                section(PROJECT_DEVELOPER, SectionKind.NAMED_ITEM, 2_200, Set.of()),
                section(TOOLCHAIN_ZOLT, SectionKind.SINGLETON, 3_000, Set.of()),
                section(TOOLCHAIN_JAVA, SectionKind.SINGLETON, 3_100, Set.of("test")),
                section(TOOLCHAIN_JAVA_TEST, SectionKind.SINGLETON, 3_200, Set.of()),
                section(VERSIONS, SectionKind.COLLECTION, 4_000, Set.of()),
                section(REPOSITORIES, SectionKind.SINGLETON, 4_100, Set.of("central", "order")),
                section(REPOSITORY, SectionKind.NAMED_ITEM, 4_200, Set.of()),
                section(CREDENTIALS, SectionKind.COLLECTION, 4_290, Set.of()),
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
                section(DEPENDENCY_LICENSE_EXCEPTIONS, SectionKind.COLLECTION, 5_105, Set.of()),
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
                section(GENERATED_TOOLS, SectionKind.COLLECTION, 6_290, Set.of()),
                section(
                        GENERATED_TOOL,
                        SectionKind.NAMED_ITEM,
                        6_300,
                        Set.of("openapi", "project", "protobuf")),
                section(GENERATED_PRESETS, SectionKind.COLLECTION, 6_390, Set.of()),
                section(GENERATED_PRESET, SectionKind.NAMED_ITEM, 6_400, Set.of()),
                section(GENERATED_MAIN_STEPS, SectionKind.COLLECTION, 6_490, Set.of()),
                section(GENERATED_MAIN, SectionKind.NAMED_ITEM, 6_500, Set.of()),
                section(GENERATED_TEST_STEPS, SectionKind.COLLECTION, 6_590, Set.of()),
                section(GENERATED_TEST, SectionKind.NAMED_ITEM, 6_600, Set.of()),
                section(TEST_SOURCES, SectionKind.SINGLETON, 6_700, Set.of()),
                section(TEST_RUNTIME, SectionKind.SINGLETON, 6_710, Set.of()),
                section(TEST_INTEGRATION, SectionKind.SINGLETON, 6_720, Set.of()),
                section(TEST_SUITES, SectionKind.COLLECTION, 6_725, Set.of()),
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
                section(PUBLISH_REPOSITORIES, SectionKind.COLLECTION, 8_090, Set.of()),
                section(PUBLISH_REPOSITORY, SectionKind.NAMED_ITEM, 8_100, Set.of()),
                section(PUBLISH_SIGNING, SectionKind.SINGLETON, 8_200, Set.of()),
                section(PUBLISH_CENTRAL, SectionKind.SINGLETON, 8_300, Set.of()),
                section(TASKS, SectionKind.COLLECTION, 8_990, Set.of()),
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
