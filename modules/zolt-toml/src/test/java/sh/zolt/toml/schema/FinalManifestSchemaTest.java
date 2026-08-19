package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
                        "coverage"),
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
                        6_900),
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
                        Map.entry("coverage", SectionKind.SINGLETON)),
                registry.sections().stream()
                        .collect(Collectors.toMap(section -> section.path().toString(), ManifestSection::kind)));
        assertEquals(Set.of("members", "project"), section("workspace").reservedChildren());
        assertEquals(Set.of("developers", "scm"), section("project").reservedChildren());
        assertEquals(Set.of("test"), section("toolchain.java").reservedChildren());
        assertEquals(Set.of("central", "order"), section("repositories").reservedChildren());
        assertEquals(
                11,
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
                        "coverage.line",
                        "coverage.branch",
                        "coverage.instruction",
                        "coverage.method"),
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
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.line"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.branch"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.instruction"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.method"));
    }

    @Test
    void limitsOneLineMutationToFrozenMutableMaps() {
        assertEquals(
                Set.of(
                        "workspace.project.license",
                        "project.license",
                        "versions.<id>",
                        "platforms.<coordinate>"),
                registry.fields().stream()
                        .filter(field -> field.formatting() == FormattingPolicy.ONE_LINE)
                        .map(field -> field.path().toString())
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of("versions.<id>", "platforms.<coordinate>"),
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

    private static ManifestPath path(String dotted) {
        return new ManifestPath(List.of(dotted.split("\\.")));
    }
}
