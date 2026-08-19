package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class FinalManifestSchemaTest {
    private final ManifestSchemaRegistry registry = FinalManifestSchema.registry();

    @Test
    void registersFrozenSectionsInCanonicalOrder() {
        assertEquals(
                List.of(
                        "workspace",
                        "workspace.members",
                        "workspace.project",
                        "project",
                        "project.scm",
                        "project.developers.<id>",
                        "toolchain.zolt",
                        "toolchain.java",
                        "toolchain.java.test",
                        "versions",
                        "repositories",
                        "repositories.<id>",
                        "credentials.<id>",
                        "platforms",
                        "dependencies",
                        "dependencies.api",
                        "dependencies.runtime",
                        "dependencies.provided",
                        "dependencies.dev",
                        "dependencies.test",
                        "dependencies.processor",
                        "dependencies.test-processor",
                        "dependencies.constraints",
                        "dependencies.policy",
                        "dependencies.policy.licenses",
                        "dependencies.license-exceptions.<coordinate>",
                        "build",
                        "build.output",
                        "build.metadata",
                        "compiler",
                        "compiler.test",
                        "compiler.generated",
                        "resources",
                        "resources.filter",
                        "resources.tokens",
                        "generated.tools.<id>",
                        "generated.presets.<id>",
                        "generated.main.<id>",
                        "generated.test.<id>",
                        "test.sources",
                        "test.runtime",
                        "test.integration",
                        "test.suites.<id>",
                        "coverage",
                        "package",
                        "package.manifest",
                        "bom",
                        "bom.versions",
                        "bom.imports",
                        "framework.spring-boot",
                        "native",
                        "publish",
                        "publish.repositories.<id>",
                        "publish.signing",
                        "publish.central",
                        "tasks.<id>",
                        "aliases"),
                sectionPaths());
        assertEquals(
                List.of(
                        1_000,
                        1_100,
                        1_200,
                        2_000,
                        2_100,
                        2_200,
                        3_000,
                        3_100,
                        3_200,
                        4_000,
                        4_100,
                        4_200,
                        4_300,
                        4_400,
                        5_000,
                        5_010,
                        5_020,
                        5_030,
                        5_040,
                        5_050,
                        5_060,
                        5_070,
                        5_080,
                        5_090,
                        5_100,
                        5_110,
                        6_000,
                        6_010,
                        6_020,
                        6_100,
                        6_110,
                        6_120,
                        6_200,
                        6_210,
                        6_220,
                        6_300,
                        6_400,
                        6_500,
                        6_600,
                        6_700,
                        6_710,
                        6_720,
                        6_730,
                        6_900,
                        7_000,
                        7_010,
                        7_100,
                        7_110,
                        7_120,
                        7_200,
                        7_300,
                        8_000,
                        8_100,
                        8_200,
                        8_300,
                        9_000,
                        9_100),
                registry.sections().stream().map(ManifestSection::canonicalOrder).toList());
        assertEquals(
                Map.ofEntries(
                        Map.entry("workspace", SectionKind.SINGLETON),
                        Map.entry("workspace.members", SectionKind.SINGLETON),
                        Map.entry("workspace.project", SectionKind.SINGLETON),
                        Map.entry("project", SectionKind.SINGLETON),
                        Map.entry("project.scm", SectionKind.SINGLETON),
                        Map.entry("project.developers.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("toolchain.zolt", SectionKind.SINGLETON),
                        Map.entry("toolchain.java", SectionKind.SINGLETON),
                        Map.entry("toolchain.java.test", SectionKind.SINGLETON),
                        Map.entry("versions", SectionKind.COLLECTION),
                        Map.entry("repositories", SectionKind.SINGLETON),
                        Map.entry("repositories.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("credentials.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("platforms", SectionKind.COLLECTION),
                        Map.entry("dependencies", SectionKind.COLLECTION),
                        Map.entry("dependencies.api", SectionKind.COLLECTION),
                        Map.entry("dependencies.runtime", SectionKind.COLLECTION),
                        Map.entry("dependencies.provided", SectionKind.COLLECTION),
                        Map.entry("dependencies.dev", SectionKind.COLLECTION),
                        Map.entry("dependencies.test", SectionKind.COLLECTION),
                        Map.entry("dependencies.processor", SectionKind.COLLECTION),
                        Map.entry("dependencies.test-processor", SectionKind.COLLECTION),
                        Map.entry("dependencies.constraints", SectionKind.COLLECTION),
                        Map.entry("dependencies.policy", SectionKind.SINGLETON),
                        Map.entry("dependencies.policy.licenses", SectionKind.SINGLETON),
                        Map.entry("dependencies.license-exceptions.<coordinate>", SectionKind.NAMED_ITEM),
                        Map.entry("build", SectionKind.SINGLETON),
                        Map.entry("build.output", SectionKind.SINGLETON),
                        Map.entry("build.metadata", SectionKind.SINGLETON),
                        Map.entry("compiler", SectionKind.SINGLETON),
                        Map.entry("compiler.test", SectionKind.SINGLETON),
                        Map.entry("compiler.generated", SectionKind.SINGLETON),
                        Map.entry("resources", SectionKind.SINGLETON),
                        Map.entry("resources.filter", SectionKind.SINGLETON),
                        Map.entry("resources.tokens", SectionKind.COLLECTION),
                        Map.entry("generated.tools.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("generated.presets.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("generated.main.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("generated.test.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("test.sources", SectionKind.SINGLETON),
                        Map.entry("test.runtime", SectionKind.SINGLETON),
                        Map.entry("test.integration", SectionKind.SINGLETON),
                        Map.entry("test.suites.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("coverage", SectionKind.SINGLETON),
                        Map.entry("package", SectionKind.SINGLETON),
                        Map.entry("package.manifest", SectionKind.COLLECTION),
                        Map.entry("bom", SectionKind.SINGLETON),
                        Map.entry("bom.versions", SectionKind.COLLECTION),
                        Map.entry("bom.imports", SectionKind.COLLECTION),
                        Map.entry("framework.spring-boot", SectionKind.SINGLETON),
                        Map.entry("native", SectionKind.SINGLETON),
                        Map.entry("publish", SectionKind.SINGLETON),
                        Map.entry("publish.repositories.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("publish.signing", SectionKind.SINGLETON),
                        Map.entry("publish.central", SectionKind.SINGLETON),
                        Map.entry("tasks.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("aliases", SectionKind.COLLECTION)),
                registry.sections().stream()
                        .collect(Collectors.toMap(section -> section.path().toString(), ManifestSection::kind)));
        assertEquals(Set.of("members", "project"), section("workspace").reservedChildren());
        assertEquals(Set.of("developers", "scm"), section("project").reservedChildren());
        assertEquals(Set.of("test"), section("toolchain.java").reservedChildren());
        assertEquals(Set.of("central", "order"), section("repositories").reservedChildren());
        assertEquals(
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
                        "license-exceptions"),
                section("dependencies").reservedChildren());
        assertEquals(Set.of("licenses"), section("dependencies.policy").reservedChildren());
        assertEquals(Set.of("metadata", "output"), section("build").reservedChildren());
        assertEquals(Set.of("generated", "test"), section("compiler").reservedChildren());
        assertEquals(Set.of("filter", "tokens"), section("resources").reservedChildren());
        assertEquals(
                Set.of("openapi", "project", "protobuf"),
                section("generated.tools.<id>").reservedChildren());
        assertEquals(Set.of("all"), section("test.suites.<id>").reservedChildren());
        assertEquals(Set.of("manifest"), section("package").reservedChildren());
        assertEquals(Set.of("imports", "versions"), section("bom").reservedChildren());
        assertEquals(
                Set.of("central", "repositories", "signing"),
                section("publish").reservedChildren());
        assertEquals(
                FinalManifestSymbols.builtInCommandNames(),
                section("tasks.<id>").reservedChildren());
        assertEquals(
                FinalManifestSymbols.builtInCommandNames(),
                section("aliases").reservedChildren());
        assertEquals(
                41,
                registry.sections().stream()
                        .filter(section -> section.reservedChildren().isEmpty())
                        .count());
    }

    @Test
    void registersEveryFrozenFieldInCanonicalOrder() {
        assertEquals(
                List.of(
                        "workspace.name",
                        "workspace.members.default",
                        "workspace.members.include",
                        "workspace.members.exclude",
                        "workspace.project.group",
                        "workspace.project.version",
                        "workspace.project.java",
                        "workspace.project.license",
                        "project.name",
                        "project.version",
                        "project.group",
                        "project.java",
                        "project.main",
                        "project.description",
                        "project.url",
                        "project.issues",
                        "project.license",
                        "project.scm.url",
                        "project.scm.connection",
                        "project.scm.developerConnection",
                        "project.scm.tag",
                        "project.developers.<id>.name",
                        "project.developers.<id>.email",
                        "project.developers.<id>.organization",
                        "project.developers.<id>.url",
                        "toolchain.zolt.version",
                        "toolchain.java.version",
                        "toolchain.java.distribution",
                        "toolchain.java.features",
                        "toolchain.java.policy",
                        "toolchain.java.test.version",
                        "toolchain.java.test.distribution",
                        "toolchain.java.test.policy",
                        "versions.<id>",
                        "repositories.central",
                        "repositories.order",
                        "repositories.<id>.url",
                        "repositories.<id>.credentials",
                        "credentials.<id>.tokenEnv",
                        "credentials.<id>.usernameEnv",
                        "credentials.<id>.passwordEnv",
                        "platforms.<coordinate>",
                        "dependencies.<coordinate>",
                        "dependencies.api.<coordinate>",
                        "dependencies.runtime.<coordinate>",
                        "dependencies.provided.<coordinate>",
                        "dependencies.dev.<coordinate>",
                        "dependencies.test.<coordinate>",
                        "dependencies.processor.<coordinate>",
                        "dependencies.test-processor.<coordinate>",
                        "dependencies.constraints.<coordinate>",
                        "dependencies.policy.conflicts",
                        "dependencies.policy.deny",
                        "dependencies.policy.licenses.allow",
                        "dependencies.policy.licenses.deny",
                        "dependencies.policy.licenses.unknown",
                        "dependencies.license-exceptions.<coordinate>.allow",
                        "dependencies.license-exceptions.<coordinate>.version",
                        "dependencies.license-exceptions.<coordinate>.reason",
                        "build.sources",
                        "build.output.root",
                        "build.output.main",
                        "build.output.test",
                        "build.output.integration",
                        "build.metadata.buildInfo",
                        "build.metadata.git",
                        "build.metadata.reproducible",
                        "compiler.encoding",
                        "compiler.jdkApi",
                        "compiler.args",
                        "compiler.test.jdkApi",
                        "compiler.test.args",
                        "compiler.generated.main",
                        "compiler.generated.test",
                        "resources.main",
                        "resources.test",
                        "resources.filter.targets",
                        "resources.filter.include",
                        "resources.filter.missing",
                        "resources.tokens.<id>",
                        "generated.tools.<id>.kind",
                        "generated.tools.<id>.coordinate",
                        "generated.tools.<id>.version",
                        "generated.tools.<id>.versionRef",
                        "generated.tools.<id>.protocCoordinate",
                        "generated.tools.<id>.protocVersion",
                        "generated.tools.<id>.protocVersionRef",
                        "generated.tools.<id>.grpcCoordinate",
                        "generated.tools.<id>.grpcVersion",
                        "generated.tools.<id>.grpcVersionRef",
                        "generated.tools.<id>.coordinates",
                        "generated.tools.<id>.mainClass",
                        "generated.tools.<id>.binary",
                        "generated.tools.<id>.versionCommand",
                        "generated.tools.<id>.versionExpect",
                        "generated.tools.<id>.allowUnpinnedTool",
                        "generated.presets.<id>.kind",
                        "generated.presets.<id>.generator",
                        "generated.presets.<id>.library",
                        "generated.presets.<id>.apiPackage",
                        "generated.presets.<id>.modelPackage",
                        "generated.presets.<id>.invokerPackage",
                        "generated.presets.<id>.config",
                        "generated.presets.<id>.templateDir",
                        "generated.presets.<id>.validateSpec",
                        "generated.presets.<id>.options",
                        "generated.presets.<id>.additionalProperties",
                        "generated.presets.<id>.configOptions",
                        "generated.presets.<id>.globalProperties",
                        "generated.presets.<id>.typeMappings",
                        "generated.presets.<id>.importMappings",
                        "generated.main.<id>.kind",
                        "generated.main.<id>.language",
                        "generated.main.<id>.tool",
                        "generated.main.<id>.mainClass",
                        "generated.main.<id>.args",
                        "generated.main.<id>.input",
                        "generated.main.<id>.inputs",
                        "generated.main.<id>.output",
                        "generated.main.<id>.produces",
                        "generated.main.<id>.into",
                        "generated.main.<id>.preset",
                        "generated.main.<id>.generator",
                        "generated.main.<id>.library",
                        "generated.main.<id>.apiPackage",
                        "generated.main.<id>.modelPackage",
                        "generated.main.<id>.invokerPackage",
                        "generated.main.<id>.config",
                        "generated.main.<id>.templateDir",
                        "generated.main.<id>.validateSpec",
                        "generated.main.<id>.options",
                        "generated.main.<id>.additionalProperties",
                        "generated.main.<id>.configOptions",
                        "generated.main.<id>.globalProperties",
                        "generated.main.<id>.typeMappings",
                        "generated.main.<id>.importMappings",
                        "generated.main.<id>.javaPackage",
                        "generated.main.<id>.grpc",
                        "generated.main.<id>.cache",
                        "generated.main.<id>.cwd",
                        "generated.main.<id>.env",
                        "generated.main.<id>.secretEnv",
                        "generated.main.<id>.inheritEnv",
                        "generated.main.<id>.timeoutSeconds",
                        "generated.main.<id>.required",
                        "generated.main.<id>.clean",
                        "generated.test.<id>.kind",
                        "generated.test.<id>.language",
                        "generated.test.<id>.tool",
                        "generated.test.<id>.mainClass",
                        "generated.test.<id>.args",
                        "generated.test.<id>.input",
                        "generated.test.<id>.inputs",
                        "generated.test.<id>.output",
                        "generated.test.<id>.produces",
                        "generated.test.<id>.into",
                        "generated.test.<id>.preset",
                        "generated.test.<id>.generator",
                        "generated.test.<id>.library",
                        "generated.test.<id>.apiPackage",
                        "generated.test.<id>.modelPackage",
                        "generated.test.<id>.invokerPackage",
                        "generated.test.<id>.config",
                        "generated.test.<id>.templateDir",
                        "generated.test.<id>.validateSpec",
                        "generated.test.<id>.options",
                        "generated.test.<id>.additionalProperties",
                        "generated.test.<id>.configOptions",
                        "generated.test.<id>.globalProperties",
                        "generated.test.<id>.typeMappings",
                        "generated.test.<id>.importMappings",
                        "generated.test.<id>.javaPackage",
                        "generated.test.<id>.grpc",
                        "generated.test.<id>.cache",
                        "generated.test.<id>.cwd",
                        "generated.test.<id>.env",
                        "generated.test.<id>.secretEnv",
                        "generated.test.<id>.inheritEnv",
                        "generated.test.<id>.timeoutSeconds",
                        "generated.test.<id>.required",
                        "generated.test.<id>.clean",
                        "test.sources.java",
                        "test.sources.groovy",
                        "test.runtime.jvmArgs",
                        "test.runtime.properties",
                        "test.runtime.env",
                        "test.runtime.events",
                        "test.integration.sources",
                        "test.integration.resources",
                        "test.suites.<id>.classes",
                        "test.suites.<id>.excludeClasses",
                        "test.suites.<id>.tags",
                        "test.suites.<id>.excludeTags",
                        "test.suites.<id>.workers",
                        "test.suites.<id>.locks",
                        "coverage.line",
                        "coverage.branch",
                        "coverage.instruction",
                        "coverage.method",
                        "package.mode",
                        "package.sources",
                        "package.javadoc",
                        "package.testJar",
                        "package.duplicates",
                        "package.manifest.<attribute>",
                        "bom.members",
                        "bom.exclude",
                        "bom.versions.<coordinate>",
                        "bom.imports.<coordinate>",
                        "framework.spring-boot.native",
                        "native.name",
                        "native.output",
                        "native.args",
                        "publish.release",
                        "publish.snapshot",
                        "publish.repositories.<id>.url",
                        "publish.repositories.<id>.credentials",
                        "publish.signing.method",
                        "publish.signing.keyId",
                        "publish.signing.passphraseEnv",
                        "publish.central.tokenEnv",
                        "publish.central.mode",
                        "publish.central.name",
                        "publish.central.url",
                        "tasks.<id>.description",
                        "tasks.<id>.run",
                        "tasks.<id>.cwd",
                        "tasks.<id>.env",
                        "aliases.<id>"),
                fieldPaths());
    }

    @Test
    void recordsExactValueKinds() {
        Map<String, ManifestValueKind> valueKinds = registry.fields().stream()
                .collect(Collectors.toMap(field -> field.path().toString(), ManifestField::valueKind));

        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.default"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.include"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.exclude"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("workspace.project.java"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("project.java"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("workspace.project.license"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("project.license"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.zolt.version"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("toolchain.java.version"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.distribution"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("toolchain.java.features"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.policy"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("toolchain.java.test.version"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.test.distribution"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.test.policy"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("versions.<id>"));
        assertEquals(
                ManifestValueKind.BOOLEAN_OR_STRING_OR_INLINE_TABLE,
                valueKinds.get("repositories.central"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("repositories.order"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("repositories.<id>.url"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("repositories.<id>.credentials"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("credentials.<id>.tokenEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("credentials.<id>.usernameEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("credentials.<id>.passwordEnv"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("platforms.<coordinate>"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("dependencies.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.api.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.runtime.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.provided.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.dev.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.test.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.processor.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.test-processor.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.constraints.<coordinate>"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("dependencies.policy.conflicts"));
        assertEquals(ManifestValueKind.INLINE_TABLE_ARRAY, valueKinds.get("dependencies.policy.deny"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("dependencies.policy.licenses.allow"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("dependencies.policy.licenses.deny"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("dependencies.policy.licenses.unknown"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("dependencies.license-exceptions.<coordinate>.allow"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("dependencies.license-exceptions.<coordinate>.version"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("dependencies.license-exceptions.<coordinate>.reason"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("build.sources"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.root"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.main"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.test"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.integration"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("build.metadata.buildInfo"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("build.metadata.git"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("build.metadata.reproducible"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.encoding"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.jdkApi"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("compiler.args"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.test.jdkApi"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("compiler.test.args"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.generated.main"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.generated.test"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.main"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.test"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.filter.targets"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.filter.include"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("resources.filter.missing"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("resources.tokens.<id>"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.sources.java"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.sources.groovy"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.runtime.jvmArgs"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("test.runtime.properties"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("test.runtime.env"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.runtime.events"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.integration.sources"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.integration.resources"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.suites.<id>.classes"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("test.suites.<id>.excludeClasses"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.suites.<id>.tags"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("test.suites.<id>.excludeTags"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("test.suites.<id>.workers"));
        assertEquals(ManifestValueKind.INLINE_TABLE_ARRAY, valueKinds.get("test.suites.<id>.locks"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.line"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.branch"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.instruction"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.method"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("package.mode"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("package.sources"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("package.javadoc"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("package.testJar"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("package.duplicates"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("package.manifest.<attribute>"));
        assertEquals(ManifestValueKind.BOOLEAN_OR_STRING_ARRAY, valueKinds.get("bom.members"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("bom.exclude"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("bom.versions.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("bom.imports.<coordinate>"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("framework.spring-boot.native"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("native.name"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("native.output"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("native.args"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.release"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.snapshot"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.repositories.<id>.url"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("publish.repositories.<id>.credentials"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.signing.method"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.signing.keyId"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.signing.passphraseEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.tokenEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.mode"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.name"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.url"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("tasks.<id>.description"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("tasks.<id>.run"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("tasks.<id>.cwd"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("tasks.<id>.env"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("aliases.<id>"));
    }

    @Test
    void recordsExactGeneratedFieldShapesAndDomainOrder() {
        assertEquals(
                List.of(
                        Map.entry("kind", ManifestValueKind.STRING),
                        Map.entry("coordinate", ManifestValueKind.STRING),
                        Map.entry("version", ManifestValueKind.STRING),
                        Map.entry("versionRef", ManifestValueKind.STRING),
                        Map.entry("protocCoordinate", ManifestValueKind.STRING),
                        Map.entry("protocVersion", ManifestValueKind.STRING),
                        Map.entry("protocVersionRef", ManifestValueKind.STRING),
                        Map.entry("grpcCoordinate", ManifestValueKind.STRING),
                        Map.entry("grpcVersion", ManifestValueKind.STRING),
                        Map.entry("grpcVersionRef", ManifestValueKind.STRING),
                        Map.entry("coordinates", ManifestValueKind.INLINE_TABLE_ARRAY),
                        Map.entry("mainClass", ManifestValueKind.STRING),
                        Map.entry("binary", ManifestValueKind.STRING),
                        Map.entry("versionCommand", ManifestValueKind.STRING_ARRAY),
                        Map.entry("versionExpect", ManifestValueKind.STRING),
                        Map.entry("allowUnpinnedTool", ManifestValueKind.BOOLEAN)),
                fieldShapes("generated.tools.<id>"));
        assertEquals(
                List.of(
                        Map.entry("kind", ManifestValueKind.STRING),
                        Map.entry("generator", ManifestValueKind.STRING),
                        Map.entry("library", ManifestValueKind.STRING),
                        Map.entry("apiPackage", ManifestValueKind.STRING),
                        Map.entry("modelPackage", ManifestValueKind.STRING),
                        Map.entry("invokerPackage", ManifestValueKind.STRING),
                        Map.entry("config", ManifestValueKind.STRING),
                        Map.entry("templateDir", ManifestValueKind.STRING),
                        Map.entry("validateSpec", ManifestValueKind.BOOLEAN),
                        Map.entry("options", ManifestValueKind.INLINE_TABLE),
                        Map.entry("additionalProperties", ManifestValueKind.INLINE_TABLE),
                        Map.entry("configOptions", ManifestValueKind.INLINE_TABLE),
                        Map.entry("globalProperties", ManifestValueKind.INLINE_TABLE),
                        Map.entry("typeMappings", ManifestValueKind.INLINE_TABLE),
                        Map.entry("importMappings", ManifestValueKind.INLINE_TABLE)),
                fieldShapes("generated.presets.<id>"));

        List<Map.Entry<String, ManifestValueKind>> stepShape = List.of(
                Map.entry("kind", ManifestValueKind.STRING),
                Map.entry("language", ManifestValueKind.STRING),
                Map.entry("tool", ManifestValueKind.STRING),
                Map.entry("mainClass", ManifestValueKind.STRING),
                Map.entry("args", ManifestValueKind.STRING_ARRAY),
                Map.entry("input", ManifestValueKind.STRING),
                Map.entry("inputs", ManifestValueKind.STRING_ARRAY),
                Map.entry("output", ManifestValueKind.STRING),
                Map.entry("produces", ManifestValueKind.STRING),
                Map.entry("into", ManifestValueKind.STRING),
                Map.entry("preset", ManifestValueKind.STRING),
                Map.entry("generator", ManifestValueKind.STRING),
                Map.entry("library", ManifestValueKind.STRING),
                Map.entry("apiPackage", ManifestValueKind.STRING),
                Map.entry("modelPackage", ManifestValueKind.STRING),
                Map.entry("invokerPackage", ManifestValueKind.STRING),
                Map.entry("config", ManifestValueKind.STRING),
                Map.entry("templateDir", ManifestValueKind.STRING),
                Map.entry("validateSpec", ManifestValueKind.BOOLEAN),
                Map.entry("options", ManifestValueKind.INLINE_TABLE),
                Map.entry("additionalProperties", ManifestValueKind.INLINE_TABLE),
                Map.entry("configOptions", ManifestValueKind.INLINE_TABLE),
                Map.entry("globalProperties", ManifestValueKind.INLINE_TABLE),
                Map.entry("typeMappings", ManifestValueKind.INLINE_TABLE),
                Map.entry("importMappings", ManifestValueKind.INLINE_TABLE),
                Map.entry("javaPackage", ManifestValueKind.STRING),
                Map.entry("grpc", ManifestValueKind.BOOLEAN),
                Map.entry("cache", ManifestValueKind.STRING),
                Map.entry("cwd", ManifestValueKind.STRING),
                Map.entry("env", ManifestValueKind.INLINE_TABLE),
                Map.entry("secretEnv", ManifestValueKind.INLINE_TABLE),
                Map.entry("inheritEnv", ManifestValueKind.STRING_ARRAY),
                Map.entry("timeoutSeconds", ManifestValueKind.INTEGER),
                Map.entry("required", ManifestValueKind.BOOLEAN),
                Map.entry("clean", ManifestValueKind.BOOLEAN));
        assertEquals(stepShape, fieldShapes("generated.main.<id>"));
        assertEquals(stepShape, fieldShapes("generated.test.<id>"));

        assertEquals(IntStream.rangeClosed(6_301, 6_316).boxed().toList(), fieldOrders("generated.tools.<id>"));
        assertEquals(IntStream.rangeClosed(6_401, 6_415).boxed().toList(), fieldOrders("generated.presets.<id>"));
        assertEquals(IntStream.rangeClosed(6_501, 6_535).boxed().toList(), fieldOrders("generated.main.<id>"));
        assertEquals(IntStream.rangeClosed(6_601, 6_635).boxed().toList(), fieldOrders("generated.test.<id>"));
    }

    @Test
    void matchesConcreteGeneratedIdsToTheirNamedSchemaItems() {
        ManifestSchemaMatch<ManifestSection> tool = registry
                .matchSection(path("generated.tools.openapi"))
                .orElseThrow();
        assertEquals("generated.tools.<id>", tool.descriptor().path().toString());
        assertEquals(Map.of("id", "openapi"), tool.bindings());

        ManifestSchemaMatch<ManifestField> input = registry
                .matchField(path("generated.main.public-api.input"))
                .orElseThrow();
        assertEquals("generated.main.<id>.input", input.descriptor().path().toString());
        assertEquals(Map.of("id", "public-api"), input.bindings());
    }

    @Test
    void recordsExactPackagingFieldShapesAndDomainOrder() {
        assertEquals(
                List.of(
                        Map.entry("mode", ManifestValueKind.STRING),
                        Map.entry("sources", ManifestValueKind.BOOLEAN),
                        Map.entry("javadoc", ManifestValueKind.BOOLEAN),
                        Map.entry("testJar", ManifestValueKind.BOOLEAN),
                        Map.entry("duplicates", ManifestValueKind.STRING),
                        Map.entry("manifest.<attribute>", ManifestValueKind.STRING)),
                fieldShapes("package"));
        assertEquals(
                List.of(
                        Map.entry("members", ManifestValueKind.BOOLEAN_OR_STRING_ARRAY),
                        Map.entry("exclude", ManifestValueKind.STRING_ARRAY),
                        Map.entry("versions.<coordinate>", ManifestValueKind.STRING_OR_INLINE_TABLE),
                        Map.entry("imports.<coordinate>", ManifestValueKind.STRING_OR_INLINE_TABLE)),
                fieldShapes("bom"));
        assertEquals(
                List.of(Map.entry("native", ManifestValueKind.BOOLEAN)),
                fieldShapes("framework.spring-boot"));
        assertEquals(
                List.of(
                        Map.entry("name", ManifestValueKind.STRING),
                        Map.entry("output", ManifestValueKind.STRING),
                        Map.entry("args", ManifestValueKind.STRING_ARRAY)),
                fieldShapes("native"));
        assertEquals(
                List.of(
                        7_001,
                        7_002,
                        7_003,
                        7_004,
                        7_005,
                        7_011,
                        7_101,
                        7_102,
                        7_111,
                        7_121,
                        7_201,
                        7_301,
                        7_302,
                        7_303),
                registry.fields().stream()
                        .filter(field -> field.canonicalOrder() >= 7_000
                                && field.canonicalOrder() < 8_000)
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void recordsExactPublishingAndCommandFieldShapesAndDomainOrder() {
        assertEquals(
                List.of(
                        Map.entry("release", ManifestValueKind.STRING),
                        Map.entry("snapshot", ManifestValueKind.STRING),
                        Map.entry("repositories.<id>.url", ManifestValueKind.STRING),
                        Map.entry("repositories.<id>.credentials", ManifestValueKind.STRING),
                        Map.entry("signing.method", ManifestValueKind.STRING),
                        Map.entry("signing.keyId", ManifestValueKind.STRING),
                        Map.entry("signing.passphraseEnv", ManifestValueKind.STRING),
                        Map.entry("central.tokenEnv", ManifestValueKind.STRING),
                        Map.entry("central.mode", ManifestValueKind.STRING),
                        Map.entry("central.name", ManifestValueKind.STRING),
                        Map.entry("central.url", ManifestValueKind.STRING)),
                fieldShapes("publish"));
        assertEquals(
                List.of(
                        Map.entry("description", ManifestValueKind.STRING),
                        Map.entry("run", ManifestValueKind.STRING_ARRAY),
                        Map.entry("cwd", ManifestValueKind.STRING),
                        Map.entry("env", ManifestValueKind.INLINE_TABLE)),
                fieldShapes("tasks.<id>"));
        assertEquals(
                List.of(Map.entry("<id>", ManifestValueKind.STRING_ARRAY)),
                fieldShapes("aliases"));
        assertEquals(
                List.of(
                        8_001,
                        8_002,
                        8_101,
                        8_102,
                        8_201,
                        8_202,
                        8_203,
                        8_301,
                        8_302,
                        8_303,
                        8_304),
                registry.fields().stream()
                        .filter(field -> field.canonicalOrder() >= 8_000
                                && field.canonicalOrder() < 9_000)
                        .map(ManifestField::canonicalOrder)
                        .toList());
        assertEquals(
                List.of(9_001, 9_002, 9_003, 9_004, 9_101),
                registry.fields().stream()
                        .filter(field -> field.canonicalOrder() >= 9_000
                                && field.canonicalOrder() < 10_000)
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void matchesDynamicPublishingAndCommandEntriesWithoutInventingNestedMapFields() {
        ManifestSchemaMatch<ManifestSection> repository = registry
                .matchSection(path("publish.repositories.company-releases"))
                .orElseThrow();
        assertEquals("publish.repositories.<id>", repository.descriptor().path().toString());
        assertEquals(Map.of("id", "company-releases"), repository.bindings());

        ManifestSchemaMatch<ManifestField> repositoryUrl = registry
                .matchField(path("publish.repositories.company-releases.url"))
                .orElseThrow();
        assertEquals(
                "publish.repositories.<id>.url",
                repositoryUrl.descriptor().path().toString());
        assertEquals(Map.of("id", "company-releases"), repositoryUrl.bindings());

        ManifestSchemaMatch<ManifestField> taskRun = registry
                .matchField(path("tasks.release-notes.run"))
                .orElseThrow();
        assertEquals("tasks.<id>.run", taskRun.descriptor().path().toString());
        assertEquals(Map.of("id", "release-notes"), taskRun.bindings());

        ManifestSchemaMatch<ManifestField> alias = registry
                .matchField(path("aliases.ci"))
                .orElseThrow();
        assertEquals("aliases.<id>", alias.descriptor().path().toString());
        assertEquals(Map.of("id", "ci"), alias.bindings());

        assertTrue(registry.matchSection(path("publish.repositories")).isEmpty());
        assertTrue(registry.matchSection(path("publish.routes")).isEmpty());
        assertTrue(registry.matchField(path("publish.routes.release")).isEmpty());
        assertTrue(registry.matchField(path("publish.artifacts")).isEmpty());
        assertTrue(registry.matchField(path("publish.central.baseUrl")).isEmpty());
        assertTrue(registry.matchField(path("publish.central.publishingType")).isEmpty());
        assertTrue(registry.matchSection(path("commands.tasks.release-notes")).isEmpty());
        assertTrue(registry.matchField(path("commands.aliases.ci")).isEmpty());
        assertTrue(registry.matchField(path("tasks.release-notes.env.RELEASE_CHANNEL")).isEmpty());
    }

    @Test
    void matchesExternalPackagingKeysAndRejectsQuarkusFrameworkTable() {
        ManifestSchemaMatch<ManifestField> attribute = registry
                .matchField(path("package.manifest.Automatic-Module-Name"))
                .orElseThrow();
        assertEquals("package.manifest.<attribute>", attribute.descriptor().path().toString());
        assertEquals(Map.of("attribute", "Automatic-Module-Name"), attribute.bindings());

        ManifestSchemaMatch<ManifestField> version = registry
                .matchField(new ManifestPath(
                        List.of("bom", "versions", "org.postgresql:postgresql")))
                .orElseThrow();
        ManifestSchemaMatch<ManifestField> imported = registry
                .matchField(new ManifestPath(
                        List.of("bom", "imports", "com.fasterxml.jackson:jackson-bom")))
                .orElseThrow();
        assertEquals("bom.versions.<coordinate>", version.descriptor().path().toString());
        assertEquals(Map.of("coordinate", "org.postgresql:postgresql"), version.bindings());
        assertEquals("bom.imports.<coordinate>", imported.descriptor().path().toString());
        assertEquals(
                Map.of("coordinate", "com.fasterxml.jackson:jackson-bom"),
                imported.bindings());

        assertTrue(registry.matchSection(path("framework.quarkus")).isEmpty());
        assertTrue(registry.matchField(path("framework.quarkus.layout")).isEmpty());
    }

    @Test
    void recordsDependencyFieldsInTheirFrozenDomainOrder() {
        assertEquals(
                List.of(
                        5_001,
                        5_011,
                        5_021,
                        5_031,
                        5_041,
                        5_051,
                        5_061,
                        5_071,
                        5_081,
                        5_091,
                        5_092,
                        5_101,
                        5_102,
                        5_103,
                        5_111,
                        5_112,
                        5_113),
                registry.fields().stream()
                        .filter(field -> field.path().toString().startsWith("dependencies."))
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void recordsBuildAndTestFieldsInTheirFrozenDomainOrder() {
        assertEquals(
                List.of(
                        6_001,
                        6_011,
                        6_012,
                        6_013,
                        6_014,
                        6_021,
                        6_022,
                        6_023,
                        6_101,
                        6_102,
                        6_103,
                        6_111,
                        6_112,
                        6_121,
                        6_122,
                        6_201,
                        6_202,
                        6_211,
                        6_212,
                        6_213,
                        6_221,
                        6_701,
                        6_702,
                        6_711,
                        6_712,
                        6_713,
                        6_714,
                        6_721,
                        6_722,
                        6_731,
                        6_732,
                        6_733,
                        6_734,
                        6_735,
                        6_736),
                registry.fields().stream()
                        .filter(field -> {
                            String path = field.path().toString();
                            return path.startsWith("build.")
                                    || path.startsWith("compiler.")
                                    || path.startsWith("resources.")
                                    || path.startsWith("test.");
                        })
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void limitsOneLineMutationToFrozenMutableMaps() {
        assertEquals(
                Set.of(
                        "workspace.project.license",
                        "project.license",
                        "versions.<id>",
                        "platforms.<coordinate>",
                        "dependencies.<coordinate>",
                        "dependencies.api.<coordinate>",
                        "dependencies.runtime.<coordinate>",
                        "dependencies.provided.<coordinate>",
                        "dependencies.dev.<coordinate>",
                        "dependencies.test.<coordinate>",
                        "dependencies.processor.<coordinate>",
                        "dependencies.test-processor.<coordinate>",
                        "dependencies.constraints.<coordinate>",
                        "resources.tokens.<id>",
                        "bom.versions.<coordinate>",
                        "bom.imports.<coordinate>"),
                registry.fields().stream()
                        .filter(field -> field.formatting() == FormattingPolicy.ONE_LINE)
                        .map(field -> field.path().toString())
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of(
                        "versions.<id>",
                        "platforms.<coordinate>",
                        "dependencies.<coordinate>",
                        "dependencies.api.<coordinate>",
                        "dependencies.runtime.<coordinate>",
                        "dependencies.provided.<coordinate>",
                        "dependencies.dev.<coordinate>",
                        "dependencies.test.<coordinate>",
                        "dependencies.processor.<coordinate>",
                        "dependencies.test-processor.<coordinate>",
                        "dependencies.constraints.<coordinate>",
                        "bom.versions.<coordinate>",
                        "bom.imports.<coordinate>"),
                registry.fields().stream()
                        .filter(field -> field.mutation() == MutationPolicy.REPLACE_ENTRY)
                        .map(field -> field.path().toString())
                        .collect(Collectors.toSet()));
        assertTrue(registry.fields().stream()
                .filter(field -> field.mutation() == MutationPolicy.NONE)
                .noneMatch(field -> field.path().toString().equals("versions.<id>")
                        || field.path().toString().equals("platforms.<coordinate>")));
        assertTrue(registry.fields().stream()
                .noneMatch(field -> field.mutation() == MutationPolicy.REPLACE_VALUE));
        assertEquals(FormattingPolicy.DEFAULT, field("dependencies.policy.conflicts").formatting());
        assertEquals(MutationPolicy.NONE, field("dependencies.policy.conflicts").mutation());
        assertEquals(FormattingPolicy.DEFAULT, field("dependencies.policy.deny").formatting());
        assertEquals(MutationPolicy.NONE, field("dependencies.policy.deny").mutation());
        assertEquals(
                FormattingPolicy.DEFAULT,
                field("dependencies.license-exceptions.<coordinate>.allow").formatting());
        assertEquals(
                MutationPolicy.NONE,
                field("dependencies.license-exceptions.<coordinate>.allow").mutation());
        assertEquals(FormattingPolicy.ONE_LINE, field("resources.tokens.<id>").formatting());
        assertEquals(MutationPolicy.NONE, field("resources.tokens.<id>").mutation());
        assertEquals(FormattingPolicy.DEFAULT, field("test.suites.<id>.locks").formatting());
        assertEquals(MutationPolicy.NONE, field("test.suites.<id>.locks").mutation());
        assertEquals(FormattingPolicy.ONE_LINE, field("bom.versions.<coordinate>").formatting());
        assertEquals(MutationPolicy.REPLACE_ENTRY, field("bom.versions.<coordinate>").mutation());
        assertEquals(FormattingPolicy.ONE_LINE, field("bom.imports.<coordinate>").formatting());
        assertEquals(MutationPolicy.REPLACE_ENTRY, field("bom.imports.<coordinate>").mutation());
    }

    private List<String> sectionPaths() {
        return registry.sections().stream().map(section -> section.path().toString()).toList();
    }

    private List<String> fieldPaths() {
        return registry.fields().stream().map(field -> field.path().toString()).toList();
    }

    private ManifestSection section(String path) {
        return registry.section(path(path)).orElseThrow();
    }

    private ManifestField field(String path) {
        return registry.field(path(path)).orElseThrow();
    }

    private List<Map.Entry<String, ManifestValueKind>> fieldShapes(String section) {
        String prefix = section + ".";
        return registry.fields().stream()
                .filter(field -> field.path().toString().startsWith(prefix))
                .map(field -> Map.entry(
                        field.path().toString().substring(prefix.length()),
                        field.valueKind()))
                .toList();
    }

    private List<Integer> fieldOrders(String section) {
        String prefix = section + ".";
        return registry.fields().stream()
                .filter(field -> field.path().toString().startsWith(prefix))
                .map(ManifestField::canonicalOrder)
                .toList();
    }

    private static ManifestPath path(String dotted) {
        return new ManifestPath(List.of(dotted.split("\\.")));
    }
}
