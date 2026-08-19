package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ManifestSchemaRegistryTest {
    @Test
    void providesImmutableCanonicalOrderAndPathLookups() {
        ManifestField name = field("project", "name", 10);
        ManifestField version = field("project", "version", 20);
        ManifestSection project = section("project", 10, Set.of());
        ManifestSection dependencies = section("dependencies", 20, Set.of("runtime", "api"));
        ArrayList<ManifestField> inputFields = new ArrayList<>(List.of(version, name));
        ArrayList<ManifestSection> inputSections = new ArrayList<>(List.of(dependencies, project));

        ManifestSchemaRegistry registry = new ManifestSchemaRegistry(
                inputFields, inputSections, new ManifestSymbolRegistry(List.of()));
        inputFields.clear();
        inputSections.clear();

        assertEquals(List.of(name, version), registry.fields());
        assertEquals(List.of(project, dependencies), registry.sections());
        assertEquals(name, registry.field(name.path()).orElseThrow());
        assertEquals(dependencies, registry.section(dependencies.path()).orElseThrow());
        assertTrue(registry.field(ManifestPath.of("unknown")).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.fields().add(name));
    }

    @Test
    void rejectsDuplicatePaths() {
        ManifestField field = field("project", "name", 10);
        ManifestSection section = section("project", 10, Set.of());

        IllegalArgumentException duplicateField = assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestSchemaRegistry(
                        List.of(field, field), List.of(section), new ManifestSymbolRegistry(List.of())));
        IllegalArgumentException duplicateSection = assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestSchemaRegistry(
                        List.of(field), List.of(section, section), new ManifestSymbolRegistry(List.of())));

        assertEquals("Duplicate manifest field path `project.name`.", duplicateField.getMessage());
        assertEquals("Duplicate manifest section path `project`.", duplicateSection.getMessage());
    }

    @Test
    void resolvesDynamicPathsWhilePreferringFixedPaths() {
        ManifestSection repositories = section("repositories", 10);
        ManifestSection repository = section("repositories.<id>", 20);
        ManifestField central = field("repositories.central", 10);
        ManifestField url = field("repositories.<id>.url", 20);
        ManifestSchemaRegistry registry = new ManifestSchemaRegistry(
                List.of(url, central),
                List.of(repository, repositories),
                new ManifestSymbolRegistry(List.of()));

        ManifestSchemaMatch<ManifestSection> repositoryMatch =
                registry.matchSection(path("repositories.company")).orElseThrow();
        ManifestSchemaMatch<ManifestField> urlMatch =
                registry.matchField(path("repositories.company.url")).orElseThrow();
        ManifestSchemaMatch<ManifestField> centralMatch =
                registry.matchField(path("repositories.central")).orElseThrow();

        assertEquals(repository, repositoryMatch.descriptor());
        assertEquals(Map.of("id", "company"), repositoryMatch.bindings());
        assertEquals(url, urlMatch.descriptor());
        assertEquals(Map.of("id", "company"), urlMatch.bindings());
        assertEquals(central, centralMatch.descriptor());
        assertEquals(Map.of(), centralMatch.bindings());
        assertEquals(Optional.empty(), registry.matchField(path("repositories.company.unknown")));
    }

    @Test
    void validatesDescriptorsAndCopiesReservedChildren() {
        LinkedHashSet<String> reserved = new LinkedHashSet<>(List.of("runtime", "api"));
        ManifestSection section = section("dependencies", 10, reserved);
        reserved.clear();

        assertEquals(List.of("api", "runtime"), List.copyOf(section.reservedChildren()));
        assertThrows(UnsupportedOperationException.class, () -> section.reservedChildren().add("test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestField(
                        ManifestPath.of("project", "name"),
                        ManifestValueKind.STRING,
                        FormattingPolicy.DEFAULT,
                        MutationPolicy.NONE,
                        -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestSection(ManifestPath.of("project"), SectionKind.SINGLETON, -1, Set.of()));
    }

    private static ManifestField field(String section, String name, int order) {
        return new ManifestField(
                ManifestPath.of(section, name),
                ManifestValueKind.STRING,
                FormattingPolicy.DEFAULT,
                MutationPolicy.REPLACE_VALUE,
                order);
    }

    private static ManifestField field(String dottedPath, int order) {
        return new ManifestField(
                path(dottedPath),
                ManifestValueKind.STRING,
                FormattingPolicy.DEFAULT,
                MutationPolicy.NONE,
                order);
    }

    private static ManifestSection section(String name, int order, Set<String> reservedChildren) {
        return new ManifestSection(
                ManifestPath.of(name),
                SectionKind.SINGLETON,
                order,
                reservedChildren);
    }

    private static ManifestSection section(String dottedPath, int order) {
        return new ManifestSection(
                path(dottedPath),
                dottedPath.contains("<") ? SectionKind.NAMED_ITEM : SectionKind.SINGLETON,
                order,
                Set.of());
    }

    private static ManifestPath path(String dottedPath) {
        return new ManifestPath(List.of(dottedPath.split("\\.")));
    }
}
