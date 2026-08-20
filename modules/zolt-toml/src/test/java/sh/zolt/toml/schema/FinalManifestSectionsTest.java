package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class FinalManifestSectionsTest extends FinalManifestSchemaTestSupport {
    @Test
    void registersFrozenSectionsInCanonicalOrder() {
        assertEquals(
                List.of(
                        "workspace",
                        "workspace.members",
                        "workspace.project",
                        "project",
                        "project.scm",
                        "project.developers",
                        "project.developers.<id>",
                        "toolchain.zolt",
                        "toolchain.java",
                        "toolchain.java.test",
                        "versions",
                        "repositories",
                        "repositories.<id>",
                        "credentials",
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
                        "dependencies.license-exceptions",
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
                        "generated.tools",
                        "generated.tools.<id>",
                        "generated.presets",
                        "generated.presets.<id>",
                        "generated.main",
                        "generated.main.<id>",
                        "generated.test",
                        "generated.test.<id>",
                        "test.sources",
                        "test.runtime",
                        "test.integration",
                        "test.suites",
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
                        "publish.repositories",
                        "publish.repositories.<id>",
                        "publish.signing",
                        "publish.central",
                        "tasks",
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
                        2_190,
                        2_200,
                        3_000,
                        3_100,
                        3_200,
                        4_000,
                        4_100,
                        4_200,
                        4_290,
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
                        5_105,
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
                        6_290,
                        6_300,
                        6_390,
                        6_400,
                        6_490,
                        6_500,
                        6_590,
                        6_600,
                        6_700,
                        6_710,
                        6_720,
                        6_725,
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
                        8_090,
                        8_100,
                        8_200,
                        8_300,
                        8_990,
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
                        Map.entry("project.developers", SectionKind.COLLECTION),
                        Map.entry("project.developers.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("toolchain.zolt", SectionKind.SINGLETON),
                        Map.entry("toolchain.java", SectionKind.SINGLETON),
                        Map.entry("toolchain.java.test", SectionKind.SINGLETON),
                        Map.entry("versions", SectionKind.COLLECTION),
                        Map.entry("repositories", SectionKind.SINGLETON),
                        Map.entry("repositories.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("credentials", SectionKind.COLLECTION),
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
                        Map.entry("dependencies.license-exceptions", SectionKind.COLLECTION),
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
                        Map.entry("generated.tools", SectionKind.COLLECTION),
                        Map.entry("generated.tools.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("generated.presets", SectionKind.COLLECTION),
                        Map.entry("generated.presets.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("generated.main", SectionKind.COLLECTION),
                        Map.entry("generated.main.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("generated.test", SectionKind.COLLECTION),
                        Map.entry("generated.test.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("test.sources", SectionKind.SINGLETON),
                        Map.entry("test.runtime", SectionKind.SINGLETON),
                        Map.entry("test.integration", SectionKind.SINGLETON),
                        Map.entry("test.suites", SectionKind.COLLECTION),
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
                        Map.entry("publish.repositories", SectionKind.COLLECTION),
                        Map.entry("publish.repositories.<id>", SectionKind.NAMED_ITEM),
                        Map.entry("publish.signing", SectionKind.SINGLETON),
                        Map.entry("publish.central", SectionKind.SINGLETON),
                        Map.entry("tasks", SectionKind.COLLECTION),
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
        assertEquals(Set.of("project"), section("generated.tools.<id>").reservedChildren());
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
                51,
                registry.sections().stream()
                        .filter(section -> section.reservedChildren().isEmpty())
                        .count());
    }

    @Test
    void namedCollectionParentsRemainDistinctFromTheirDynamicItems() {
        Map<String, String> parentsByItem = Map.ofEntries(
                Map.entry("project.developers.<id>", "project.developers"),
                Map.entry("credentials.<id>", "credentials"),
                Map.entry("dependencies.license-exceptions.<coordinate>", "dependencies.license-exceptions"),
                Map.entry("generated.tools.<id>", "generated.tools"),
                Map.entry("generated.presets.<id>", "generated.presets"),
                Map.entry("generated.main.<id>", "generated.main"),
                Map.entry("generated.test.<id>", "generated.test"),
                Map.entry("test.suites.<id>", "test.suites"),
                Map.entry("publish.repositories.<id>", "publish.repositories"),
                Map.entry("tasks.<id>", "tasks"));

        parentsByItem.forEach((item, parent) -> {
            assertEquals(SectionKind.COLLECTION, section(parent).kind());
            assertEquals(Set.of(), section(parent).reservedChildren());
            assertEquals(Map.of(), section(parent).dynamicKeyGrammars());
            assertEquals(SectionKind.NAMED_ITEM, section(item).kind());
            assertEquals(
                    path(parent),
                    new ManifestPath(section(item).path().segments().subList(
                            0,
                            section(item).path().segments().size() - 1)));
            String placeholder = section(item).path().segments().getLast();
            assertEquals(
                    Set.of(placeholder.substring(1, placeholder.length() - 1)),
                    section(item).dynamicKeyGrammars().keySet());
        });

        assertEquals(Set.of("project"), section("generated.tools.<id>").reservedChildren());
        assertEquals(Set.of("all"), section("test.suites.<id>").reservedChildren());
        assertEquals(
                FinalManifestSymbols.builtInCommandNames(),
                section("tasks.<id>").reservedChildren());
    }
}
