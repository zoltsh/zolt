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
    void registersOnlyCoreSectionsInCanonicalOrder() {
        assertEquals(
                List.of(
                        "workspace",
                        "workspace.members",
                        "workspace.project",
                        "project",
                        "project.scm",
                        "project.developers.<id>"),
                sectionPaths());
        assertEquals(
                List.of(1_000, 1_100, 1_200, 2_000, 2_100, 2_200),
                registry.sections().stream().map(ManifestSection::canonicalOrder).toList());
        assertEquals(SectionKind.NAMED_ITEM, section("project.developers.<id>").kind());
        assertEquals(Set.of("members", "project"), section("workspace").reservedChildren());
        assertEquals(Set.of("developers", "scm"), section("project").reservedChildren());
    }

    @Test
    void registersEveryCoreFieldInCanonicalOrder() {
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
                        "project.developers.<id>.url"),
                fieldPaths());
    }

    @Test
    void recordsExactCoreValueAndFormattingPolicies() {
        Map<String, ManifestValueKind> valueKinds = registry.fields().stream()
                .collect(Collectors.toMap(field -> field.path().toString(), ManifestField::valueKind));

        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.default"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.include"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.exclude"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("workspace.project.java"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("project.java"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("workspace.project.license"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("project.license"));
        assertEquals(FormattingPolicy.ONE_LINE, field("workspace.project.license").formatting());
        assertEquals(FormattingPolicy.ONE_LINE, field("project.license").formatting());
        assertEquals(23, registry.fields().stream()
                .filter(field -> field.formatting() == FormattingPolicy.DEFAULT)
                .count());
        assertTrue(registry.fields().stream().allMatch(field -> field.mutation() == MutationPolicy.NONE));
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
