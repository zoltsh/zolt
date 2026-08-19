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

    private static final ManifestSchemaRegistry REGISTRY = new ManifestSchemaRegistry(fields(), sections());

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
                section(PROJECT_DEVELOPER, SectionKind.NAMED_ITEM, 2_200, Set.of()));
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
                field(PROJECT_DEVELOPER, "url", ManifestValueKind.STRING, 2_240));
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

    private static ManifestSection section(
            ManifestPath path,
            SectionKind kind,
            int canonicalOrder,
            Set<String> reservedChildren) {
        return new ManifestSection(path, kind, canonicalOrder, reservedChildren);
    }
}
